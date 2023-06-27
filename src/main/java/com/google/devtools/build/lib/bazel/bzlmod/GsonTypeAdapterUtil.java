// Copyright 2023 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.DICT;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_BIMAP;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_LIST;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_MAP;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_SET;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.ryanharter.auto.value.gson.GenerateTypeAdapter;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Utility class to hold type adapters and helper methods to get gson registered with type adapters
 */
public final class GsonTypeAdapterUtil {

  public static final TypeAdapter<Version> VERSION_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, Version version) throws IOException {
          jsonWriter.value(version.toString());
        }

        @Override
        public Version read(JsonReader jsonReader) throws IOException {
          Version version;
          String versionString = jsonReader.nextString();
          try {
            version = Version.parse(versionString);
          } catch (ParseException e) {
            throw new JsonParseException(
                String.format("Unable to parse Version %s from the lockfile", versionString), e);
          }
          return version;
        }
      };

  public static final TypeAdapter<ModuleKey> MODULE_KEY_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, ModuleKey moduleKey) throws IOException {
          jsonWriter.value(moduleKey.toString());
        }

        @Override
        public ModuleKey read(JsonReader jsonReader) throws IOException {
          String jsonString = jsonReader.nextString();
          if (jsonString.equals("<root>")) {
            return ModuleKey.ROOT;
          }
          List<String> parts = Splitter.on('@').splitToList(jsonString);
          if (parts.get(1).equals("_")) {
            return ModuleKey.create(parts.get(0), Version.EMPTY);
          }

          Version version;
          try {
            version = Version.parse(parts.get(1));
          } catch (ParseException e) {
            throw new JsonParseException(
                String.format("Unable to parse ModuleKey %s version from the lockfile", jsonString),
                e);
          }
          return ModuleKey.create(parts.get(0), version);
        }
      };

  // TODO(salmasamy) need to handle "isolated" in module extensions when it is stable
  public static final TypeAdapter<ModuleExtensionId> MODULE_EXTENSION_ID_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, ModuleExtensionId moduleExtId) throws IOException {
          jsonWriter.value(moduleExtId.getBzlFileLabel() + "%" + moduleExtId.getExtensionName());
        }

        @Override
        public ModuleExtensionId read(JsonReader jsonReader) throws IOException {
          String jsonString = jsonReader.nextString();
          // [0] is labelString, [1] is extensionName
          List<String> extIdParts = Splitter.on("%").splitToList(jsonString);
          try {
            return ModuleExtensionId.create(
                Label.parseCanonical(extIdParts.get(0)), extIdParts.get(1), Optional.empty());
          } catch (LabelSyntaxException e) {
            throw new JsonParseException(
                String.format(
                    "Unable to parse ModuleExtensionID bzl file label:  '%s' from the lockfile",
                    extIdParts.get(0)),
                e);
          }
        }
      };

  public static final TypeAdapter<byte[]> BYTE_ARRAY_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, byte[] value) throws IOException {
          jsonWriter.value(Base64.getEncoder().encodeToString(value));
        }

        @Override
        public byte[] read(JsonReader jsonReader) throws IOException {
          return Base64.getDecoder().decode(jsonReader.nextString());
        }
      };

  public static final TypeAdapterFactory OPTIONAL =
      new TypeAdapterFactory() {
        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
          if (typeToken.getRawType() != Optional.class) {
            return null;
          }
          Type type = typeToken.getType();
          if (!(type instanceof ParameterizedType)) {
            return null;
          }
          Type elementType = ((ParameterizedType) typeToken.getType()).getActualTypeArguments()[0];
          var elementTypeAdapter = gson.getAdapter(TypeToken.get(elementType));
          if (elementTypeAdapter == null) {
            return null;
          }
          return (TypeAdapter<T>) new OptionalTypeAdapter<>(elementTypeAdapter);
        }
      };

  private static final class OptionalTypeAdapter<T> extends TypeAdapter<Optional<T>> {
    private final TypeAdapter<T> elementTypeAdapter;

    public OptionalTypeAdapter(TypeAdapter<T> elementTypeAdapter) {
      this.elementTypeAdapter = elementTypeAdapter;
    }

    @Override
    public void write(JsonWriter jsonWriter, Optional<T> t) throws IOException {
      Preconditions.checkNotNull(t);
      if (t.isEmpty()) {
        jsonWriter.nullValue();
      } else {
        elementTypeAdapter.write(jsonWriter, t.get());
      }
    }

    @Override
    public Optional<T> read(JsonReader jsonReader) throws IOException {
      if (jsonReader.peek() == JsonToken.NULL) {
        jsonReader.nextNull();
        return Optional.empty();
      } else {
        return Optional.of(elementTypeAdapter.read(jsonReader));
      }
    }
  }

  public static final Gson LOCKFILE_GSON =
      new GsonBuilder()
          .setPrettyPrinting()
          .disableHtmlEscaping()
          .enableComplexMapKeySerialization()
          .registerTypeAdapterFactory(GenerateTypeAdapter.FACTORY)
          .registerTypeAdapterFactory(DICT)
          .registerTypeAdapterFactory(IMMUTABLE_MAP)
          .registerTypeAdapterFactory(IMMUTABLE_LIST)
          .registerTypeAdapterFactory(IMMUTABLE_BIMAP)
          .registerTypeAdapterFactory(IMMUTABLE_SET)
          .registerTypeAdapterFactory(OPTIONAL)
          .registerTypeAdapter(Version.class, VERSION_TYPE_ADAPTER)
          .registerTypeAdapter(ModuleKey.class, MODULE_KEY_TYPE_ADAPTER)
          .registerTypeAdapter(ModuleExtensionId.class, MODULE_EXTENSION_ID_TYPE_ADAPTER)
          .registerTypeAdapter(AttributeValues.class, new AttributeValuesAdapter())
          .registerTypeAdapter(byte[].class, BYTE_ARRAY_TYPE_ADAPTER)
          .create();

  private GsonTypeAdapterUtil() {}
}
