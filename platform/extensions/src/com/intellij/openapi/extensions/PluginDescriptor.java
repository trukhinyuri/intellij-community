// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.extensions;

import com.intellij.openapi.util.NlsSafe;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.ApiStatus.Internal;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.Date;

@ApiStatus.NonExtendable
public interface PluginDescriptor {
  @NotNull PluginId getPluginId();

  @Nullable ClassLoader getPluginClassLoader();

  @ApiStatus.Experimental
  default @NotNull ClassLoader getClassLoader() {
    ClassLoader classLoader = getPluginClassLoader();
    return classLoader == null ? getClass().getClassLoader() : classLoader;
  }

  default boolean isBundled() {
    return false;
  }

  /** @deprecated Use {@link #getPluginPath()} */
  @Deprecated
  default File getPath() {
    Path path = getPluginPath();
    return path == null ? null : path.toFile();
  }

  Path getPluginPath();

  @Nullable @Nls String getDescription();

  @Nullable String getChangeNotes();

  @NlsSafe String getName();

  @Nullable String getProductCode();

  @Nullable Date getReleaseDate();

  int getReleaseVersion();

  boolean isLicenseOptional();

  @Nullable @NlsSafe String getVendor();

  default @Nullable @NlsSafe String getOrganization() {
    return null;
  }

  @NlsSafe String getVersion();

  @Nullable String getResourceBundleBaseName();

  @Nullable @NlsSafe String getCategory();

  default @Nullable @Nls String getDisplayCategory() { return getCategory(); }

  @Nullable String getVendorEmail();

  @Nullable String getVendorUrl();

  @Nullable String getUrl();

  @Nullable @NlsSafe String getSinceBuild();

  @Nullable @NlsSafe String getUntilBuild();

  default boolean allowBundledUpdate() {
    return false;
  }

  /**
   * If true, this plugin is hidden from the list of installed plugins in Settings | Plugins.
   */
  @Internal
  default boolean isImplementationDetail() {
    return false;
  }

  /**
   * If true, this plugin requires restart even if it otherwise fulfills the requirements of dynamic plugins.
   */
  default boolean isRequireRestart() { return false; }

  /**
   * @deprecated This method does not reflect the enabled/disabled state of the plugin (as the tick box in the settings shows).
   * Its effective semantics in the current implementation are rather about loaded state,
   * but it cannot be completely trusted too due to public mutability.
   * <br>
   * Note that if a plugin is marked as enabled, it does not mean it is loaded, and vice versa.
   * <br>
   * Instead, use {@link com.intellij.ide.plugins.PluginManagerCore#isLoaded(PluginId)},
   * {@link com.intellij.ide.plugins.PluginManagerCore#isDisabled(PluginId)}.
   */
  @Deprecated
  boolean isEnabled();

  /**
   * @deprecated for removal. This method has no immediate effect and is, in fact, an implementation detail of plugin loading.
   * <br>
   * Instead, use {@link com.intellij.ide.plugins.PluginManagerCore#disablePlugin(PluginId)} and
   * {@link com.intellij.ide.plugins.PluginManagerCore#enablePlugin(PluginId)}.
   * Also, see {@link com.intellij.ide.plugins.PluginEnabler}.
   */
  @Deprecated
  void setEnabled(boolean enabled);
}
