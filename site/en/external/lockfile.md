Project: /_project.yaml
Book: /_book.yaml

# Bazel Lockfile

{% include "_buttons.html" %}

A Lockfile records the specific versions or dependencies of software libraries 
or packages required by the project by storing the results of the module and 
module extensions resolution. It enables reproducible builds, allowing users to
make sure they are crating the same development environment. 
It also enhances build efficiency by enabling Bazel to skip the resolution
process if there are no changes in the project dependencies. Furthermore, the 
lockfile enhances stability by preventing unexpected updates or breaking changes 
in external libraries, thereby reducing the risk of introducing bugs.

## How It Works

The lockfile is generated under the working directory with the name
`MODULE.bazel.lock`. It stores the current state of the project (MODULE file, 
flags, overrides ...etc) and the result of the resolution. With each build, bazel
will compare the project state with the one stored in the lockfile and decide
what to do next depending on the selected `Mode`. This mode can be controlled
via the flag `lockfile_mode` that accepts the following values: 

* `Update`: If the project state matches the lockfile, return result from 
the lockfile without running resolution. Else, run resolution and update the 
lockfile to the current state.
* `Error`: If the project state matches the lockfile, return result from
  the lockfile. Else, throw an error with the differences.
* `Off`: Stop using the lockfile at all.


## Lockfile Contents

The lockfile stores all the needed information to decide whether the project 
state changed or not, and the result of building the project in the current state.
We can say it mainly consists of two main parts:
1. Inputs of the module resolution (`moduleFileHash`, `flags` and `localOverrideHashes`)
and output of it (`moduleDepGraph`).
2. For each module extension, inputs that affects it (`transitiveDigest`) and the
output of running that extension (`generatedRepoSpecs`)

Following an example of how the lockfile would look like, with more explanation 
of each section.

```json
{
  "lockFileVersion": 1,
  "moduleFileHash": "b0f47b98a67ee15f9.......8dff8721c66b721e370",
  "flags": {
    "cmdRegistries": [
      "https://bcr.bazel.build/"
    ],
    "cmdModuleOverrides": {},
    "allowedYankedVersions": [],
    "envVarAllowedYankedVersions": "",
    "ignoreDevDependency": false,
    "directDependenciesMode": "WARNING",
    "compatibilityMode": "ERROR"
  },
  "localOverrideHashes": {
    "bazel_tools": "b5ae1fa37632140aff8.......15c6fe84a1231d6af9"
  },
  "moduleDepGraph": {
    "<root>": {
      "name": "",
      "version": "",
      "executionPlatformsToRegister": [],
      "toolchainsToRegister": [],
      "extensionUsages": [
        {
          "extensionBzlFile": "extension.bzl",
          "extensionName": "lockfile_ext"
        }
      ],
      .
      .
      .
    }
  },
  "moduleExtensions": {
    "//:extension.bzl%lockfile_ext": {
      "transitiveDigest": "oWDzxG/aLnyY6Ubrfy....+Jp6maQvEPxn0pBM=",
      "generatedRepoSpecs": {
        "hello": {
          "bzlFile": "@@//:extension.bzl",
          .
          .
          .
        }
      }
    }
  }
}
```

### ModuleFileHash

The hash of the `MODULE.bazel` file contents. If anything changes in this file,
the hash will be different.

### Flags

This object stores all the flags that can affect the result of the resolution.

### LocalOverrideHashes

If the root module has `locel_path_overrides`, then this section would store the
hash of this local repo `MODULE.bazel` file to be able to also track the changes
to this dependency.

### ModuleDepGraph

This is the result of the running resolution with all the above input, which is
the dependency graph of all the modules needed to run this project.

### ModuleExtensions

This is a map of all the extensions _used_ in the project. If an extension exists,
but it is not used in any module, it won't be included in this map.
For each used extension there is an entry with its Identifier (the containing 
file and name) as the key, and the value is:
1. All the data that affects the build of this extension (`transitiveDigest`)
2. The `generatedRepoSpecs` which is the result of running that extension
with the current input.

There is one more thing that can affect the results of this extension, which is its
_usages_. We don't store that here because it is already stored as part of each module
data in `extensionUsages` section. But it is being considered when comparing the current
state of the extension with the one in the lockfile.







