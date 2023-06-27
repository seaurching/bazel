// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.collect.ImmutableBiMap.toImmutableBiMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.rules.repository.NeedsSkyframeRestartException;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction.BzlLoadFailedException;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.spelling.SpellChecker;
import net.starlark.java.syntax.Location;

/**
 * Evaluates a single module extension. This function loads the .bzl file containing the extension,
 * runs its implementation function with a module_ctx object containing all relevant information,
 * and returns the generated repos.
 */
public class SingleExtensionEvalFunction implements SkyFunction {
  private final BlazeDirectories directories;
  private final Supplier<Map<String, String>> clientEnvironmentSupplier;
  private final DownloadManager downloadManager;

  private double timeoutScaling = 1.0;
  @Nullable private ProcessWrapper processWrapper = null;
  @Nullable private RepositoryRemoteExecutor repositoryRemoteExecutor = null;

  public SingleExtensionEvalFunction(
      BlazeDirectories directories,
      Supplier<Map<String, String>> clientEnvironmentSupplier,
      DownloadManager downloadManager) {
    this.directories = directories;
    this.clientEnvironmentSupplier = clientEnvironmentSupplier;
    this.downloadManager = downloadManager;
  }

  public void setTimeoutScaling(double timeoutScaling) {
    this.timeoutScaling = timeoutScaling;
  }

  public void setProcessWrapper(ProcessWrapper processWrapper) {
    this.processWrapper = processWrapper;
  }

  public void setRepositoryRemoteExecutor(RepositoryRemoteExecutor repositoryRemoteExecutor) {
    this.repositoryRemoteExecutor = repositoryRemoteExecutor;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SingleExtensionEvalFunctionException, InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }

    ModuleExtensionId extensionId = (ModuleExtensionId) skyKey.argument();
    SingleExtensionUsagesValue usagesValue =
        (SingleExtensionUsagesValue) env.getValue(SingleExtensionUsagesValue.key(extensionId));
    if (usagesValue == null) {
      return null;
    }
    Location sampleUsageLocation =
        usagesValue.getExtensionUsages().values().iterator().next().getLocation();
    BzlLoadValue bzlLoadValue =
        loadBzlFile(extensionId.getBzlFileLabel(), sampleUsageLocation, starlarkSemantics, env);
    if (bzlLoadValue == null) {
      return null;
    }
    // TODO(wyv): Consider whether there's a need to check .bzl load visibility
    // (BzlLoadFunction#checkLoadVisibilities).
    // TODO(wyv): Consider refactoring to use PackageFunction#loadBzlModules, or the simpler API
    // that may be created by b/237658764.

    // Check that the .bzl file actually exports a module extension by our name.
    Object exported = bzlLoadValue.getModule().getGlobal(extensionId.getExtensionName());
    if (!(exported instanceof ModuleExtension)) {
      ImmutableSet<String> exportedExtensions =
          bzlLoadValue.getModule().getGlobals().entrySet().stream()
              .filter(e -> e.getValue() instanceof ModuleExtension)
              .map(Entry::getKey)
              .collect(toImmutableSet());
      throw new SingleExtensionEvalFunctionException(
          ExternalDepsException.withMessage(
              ExternalDeps.Code.BAD_MODULE,
              "%s does not export a module extension called %s, yet its use is requested at %s%s",
              extensionId.getBzlFileLabel(),
              extensionId.getExtensionName(),
              sampleUsageLocation,
              SpellChecker.didYouMean(extensionId.getExtensionName(), exportedExtensions)),
          Transience.PERSISTENT);
    }

    // Check the lockfile first for that module extension
    byte[] bzlTransitiveDigest =
        BazelModuleContext.of(bzlLoadValue.getModule()).bzlTransitiveDigest();
    LockfileMode lockfileMode = BazelLockFileFunction.LOCKFILE_MODE.get(env);
    if (!lockfileMode.equals(LockfileMode.OFF)) {
      BazelLockFileValue lockfile = (BazelLockFileValue) env.getValue(BazelLockFileValue.KEY);
      if (lockfile == null) {
        return null;
      }
      SingleExtensionEvalValue singleExtensionEvalValue =
          tryGettingValueFromLockFile(
              extensionId, usagesValue, bzlTransitiveDigest, lockfileMode, lockfile);
      if (singleExtensionEvalValue != null) {
        return singleExtensionEvalValue;
      }
    }

    // Run that extension!
    ModuleExtension extension = (ModuleExtension) exported;
    ImmutableMap<String, RepoSpec> generatedRepoSpecs =
        runModuleExtension(
            extensionId, extension, usagesValue, bzlLoadValue.getModule(), starlarkSemantics, env);
    if (generatedRepoSpecs == null) {
      return null;
    }
    // Check that all imported repos have been actually generated
    validateAllImportsAreGenerated(generatedRepoSpecs, usagesValue, extensionId);

    if (lockfileMode.equals(LockfileMode.UPDATE)) {
      env.getListener()
          .post(
              ModuleExtensionResolutionEvent.create(
                  extensionId,
                  LockFileModuleExtension.create(bzlTransitiveDigest, generatedRepoSpecs)));
    }
    return createSingleExtentionValue(generatedRepoSpecs, usagesValue);
  }

  @Nullable
  private SingleExtensionEvalValue tryGettingValueFromLockFile(
      ModuleExtensionId extensionId,
      SingleExtensionUsagesValue usagesValue,
      byte[] bzlTransitiveDigest,
      LockfileMode lockfileMode,
      BazelLockFileValue lockfile)
      throws SingleExtensionEvalFunctionException {
    LockFileModuleExtension lockedExtension = lockfile.getModuleExtensions().get(extensionId);
    ImmutableMap<ModuleKey, ModuleExtensionUsage> lockedExtensionUsages;
    try {
      // TODO(salmasamy) might be nicer to precompute this table when we construct
      // BazelLockFileValue, without adding it to the json file
      ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage> extensionUsagesById =
          BazelDepGraphFunction.getExtensionUsagesById(lockfile.getModuleDepGraph());
      lockedExtensionUsages = extensionUsagesById.row(extensionId);
    } catch (ExternalDepsException e) {
      throw new SingleExtensionEvalFunctionException(e, Transience.PERSISTENT);
    }

    // If we have the extension, check if the implementation and usage haven't changed
    if (lockedExtension != null
        && Arrays.equals(bzlTransitiveDigest, lockedExtension.getBzlTransitiveDigest())
        && usagesValue.getExtensionUsages().equals(lockedExtensionUsages)) {
      return createSingleExtentionValue(lockedExtension.getGeneratedRepoSpecs(), usagesValue);
    } else if (lockfileMode.equals(LockfileMode.ERROR)) {
      ImmutableList<String> extDiff =
          lockfile.getModuleExtensionDiff(
              lockedExtension,
              lockedExtensionUsages,
              extensionId,
              bzlTransitiveDigest,
              usagesValue.getExtensionUsages());
      throw new SingleExtensionEvalFunctionException(
          ExternalDepsException.withMessage(
              Code.BAD_MODULE,
              "Lock file is no longer up-to-date because: %s",
              String.join(", ", extDiff)),
          Transience.PERSISTENT);
    }
    return null;
  }

  private SingleExtensionEvalValue createSingleExtentionValue(
      ImmutableMap<String, RepoSpec> generatedRepoSpecs, SingleExtensionUsagesValue usagesValue) {
    return SingleExtensionEvalValue.create(
        generatedRepoSpecs,
        generatedRepoSpecs.keySet().stream()
            .collect(
                toImmutableBiMap(
                    e ->
                        RepositoryName.createUnvalidated(
                            usagesValue.getExtensionUniqueName() + "~" + e),
                    Function.identity())));
  }

  private void validateAllImportsAreGenerated(
      ImmutableMap<String, RepoSpec> generatedRepoSpecs,
      SingleExtensionUsagesValue usagesValue,
      ModuleExtensionId extensionId)
      throws SingleExtensionEvalFunctionException {
    for (ModuleExtensionUsage usage : usagesValue.getExtensionUsages().values()) {
      for (Entry<String, String> repoImport : usage.getImports().entrySet()) {
        if (!generatedRepoSpecs.containsKey(repoImport.getValue())) {
          throw new SingleExtensionEvalFunctionException(
              ExternalDepsException.withMessage(
                  Code.BAD_MODULE,
                  "module extension \"%s\" from \"%s\" does not generate repository \"%s\", yet it"
                      + " is imported as \"%s\" in the usage at %s%s",
                  extensionId.getExtensionName(),
                  extensionId.getBzlFileLabel(),
                  repoImport.getValue(),
                  repoImport.getKey(),
                  usage.getLocation(),
                  SpellChecker.didYouMean(repoImport.getValue(), generatedRepoSpecs.keySet())),
              Transience.PERSISTENT);
        }
      }
    }
  }

  private BzlLoadValue loadBzlFile(
      Label bzlFileLabel,
      Location sampleUsageLocation,
      StarlarkSemantics starlarkSemantics,
      Environment env)
      throws SingleExtensionEvalFunctionException, InterruptedException {
    // Check that the .bzl label isn't crazy.
    try {
      BzlLoadFunction.checkValidLoadLabel(bzlFileLabel, starlarkSemantics);
    } catch (LabelSyntaxException e) {
      throw new SingleExtensionEvalFunctionException(
          ExternalDepsException.withCauseAndMessage(
              Code.BAD_MODULE, e, "invalid module extension label"),
          Transience.PERSISTENT);
    }

    // Load the .bzl file pointed to by the label.
    BzlLoadValue bzlLoadValue;
    try {
      bzlLoadValue =
          (BzlLoadValue)
              env.getValueOrThrow(
                  BzlLoadValue.keyForBzlmod(bzlFileLabel), BzlLoadFailedException.class);
    } catch (BzlLoadFailedException e) {
      throw new SingleExtensionEvalFunctionException(
          ExternalDepsException.withCauseAndMessage(
              Code.BAD_MODULE,
              e,
              "Error loading '%s' for module extensions, requested by %s: %s",
              bzlFileLabel,
              sampleUsageLocation,
              e.getMessage()),
          Transience.PERSISTENT);
    }
    return bzlLoadValue;
  }

  @Nullable
  private ImmutableMap<String, RepoSpec> runModuleExtension(
      ModuleExtensionId extensionId,
      ModuleExtension extension,
      SingleExtensionUsagesValue usagesValue,
      net.starlark.java.eval.Module module,
      StarlarkSemantics starlarkSemantics,
      Environment env)
      throws SingleExtensionEvalFunctionException, InterruptedException {
    ModuleExtensionEvalStarlarkThreadContext threadContext =
        new ModuleExtensionEvalStarlarkThreadContext(
            usagesValue.getExtensionUniqueName() + "~",
            extensionId.getBzlFileLabel().getPackageIdentifier(),
            BazelModuleContext.of(module).repoMapping(),
            directories,
            env.getListener());
    try (Mutability mu =
        Mutability.create("module extension", usagesValue.getExtensionUniqueName())) {
      StarlarkThread thread = new StarlarkThread(mu, starlarkSemantics);
      thread.setPrintHandler(Event.makeDebugPrintHandler(env.getListener()));
      ModuleExtensionContext moduleContext =
          createContext(env, usagesValue, starlarkSemantics, extensionId, extension);
      threadContext.storeInThread(thread);
      try {
        Object returnValue =
            Starlark.fastcall(
                thread, extension.getImplementation(), new Object[] {moduleContext}, new Object[0]);
        if (returnValue != Starlark.NONE && !(returnValue instanceof ModuleExtensionMetadata)) {
          throw new SingleExtensionEvalFunctionException(
              ExternalDepsException.withMessage(
                  ExternalDeps.Code.BAD_MODULE,
                  "expected module extension %s in %s to return None or extension_metadata, got %s",
                  extensionId.getExtensionName(),
                  extensionId.getBzlFileLabel(),
                  Starlark.type(returnValue)),
              Transience.PERSISTENT);
        }
        if (returnValue instanceof ModuleExtensionMetadata) {
          ModuleExtensionMetadata metadata = (ModuleExtensionMetadata) returnValue;
          metadata.evaluate(
              usagesValue.getExtensionUsages().values(),
              threadContext.getGeneratedRepoSpecs().keySet(),
              env.getListener());
        }
      } catch (NeedsSkyframeRestartException e) {
        // Clean up and restart by returning null.
        try {
          if (moduleContext.getWorkingDirectory().exists()) {
            moduleContext.getWorkingDirectory().deleteTree();
          }
        } catch (IOException e1) {
          ExternalDepsException externalDepsException =
              ExternalDepsException.withCauseAndMessage(
                  ExternalDeps.Code.UNRECOGNIZED,
                  e1,
                  "Failed to clean up module context directory");
          throw new SingleExtensionEvalFunctionException(
              externalDepsException, Transience.TRANSIENT);
        }
        return null;
      } catch (EvalException e) {
        env.getListener().handle(Event.error(e.getMessageWithStack()));
        throw new SingleExtensionEvalFunctionException(
            ExternalDepsException.withMessage(
                ExternalDeps.Code.BAD_MODULE,
                "error evaluating module extension %s in %s",
                extensionId.getExtensionName(),
                extensionId.getBzlFileLabel()),
            Transience.TRANSIENT);
      }
    }
    return threadContext.getGeneratedRepoSpecs();
  }

  private ModuleExtensionContext createContext(
      Environment env,
      SingleExtensionUsagesValue usagesValue,
      StarlarkSemantics starlarkSemantics,
      ModuleExtensionId extensionId,
      ModuleExtension extension)
      throws SingleExtensionEvalFunctionException {
    Path workingDirectory =
        directories
            .getOutputBase()
            .getRelative(LabelConstants.MODULE_EXTENSION_WORKING_DIRECTORY_LOCATION)
            .getRelative(usagesValue.getExtensionUniqueName());
    ArrayList<StarlarkBazelModule> modules = new ArrayList<>();
    for (AbridgedModule abridgedModule : usagesValue.getAbridgedModules()) {
      ModuleKey moduleKey = abridgedModule.getKey();
      try {
        modules.add(
            StarlarkBazelModule.create(
                abridgedModule,
                extension,
                usagesValue.getRepoMappings().get(moduleKey),
                usagesValue.getExtensionUsages().get(moduleKey)));
      } catch (ExternalDepsException e) {
        throw new SingleExtensionEvalFunctionException(e, Transience.PERSISTENT);
      }
    }
    return new ModuleExtensionContext(
        workingDirectory,
        env,
        clientEnvironmentSupplier.get(),
        downloadManager,
        timeoutScaling,
        processWrapper,
        starlarkSemantics,
        repositoryRemoteExecutor,
        extensionId,
        StarlarkList.immutableCopyOf(modules));
  }

  static final class SingleExtensionEvalFunctionException extends SkyFunctionException {

    SingleExtensionEvalFunctionException(ExternalDepsException cause, Transience transience) {
      super(cause, transience);
    }
  }
}
