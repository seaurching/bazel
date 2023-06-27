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

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.ryanharter.auto.value.gson.GenerateTypeAdapter;

/**
 * This object serves as a container for the transitive digest (obtained from transitive .bzl files)
 * and the generated repositories from evaluating a module extension. Its purpose is to store this
 * information within the lockfile.
 */
@AutoValue
@GenerateTypeAdapter
public abstract class LockFileModuleExtension implements Postable {

  @SuppressWarnings("AutoValueImmutableFields")
  public abstract byte[] getBzlTransitiveDigest();

  public abstract ImmutableMap<String, RepoSpec> getGeneratedRepoSpecs();

  public static LockFileModuleExtension create(
      byte[] transitiveDigest, ImmutableMap<String, RepoSpec> generatedRepoSpecs) {
    return new AutoValue_LockFileModuleExtension(transitiveDigest, generatedRepoSpecs);
  }
}
