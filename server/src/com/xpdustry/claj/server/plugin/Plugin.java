
package com.xpdustry.claj.server.plugin;

import arc.files.Fi;
import arc.util.CommandHandler;

import com.xpdustry.claj.server.ClajVars;
import com.xpdustry.claj.server.util.JsonSettings;


public interface Plugin {
  /** @return a new logger for this plugin. The plugin is automatically determined using the caller class. */
  static PluginLogger getLogger() {
    return ClajVars.plugins.getLogger();
  }

  /**
   * @return a new logger for this plugin with the specified {@code topicClass}.
   *         The plugin is automatically determined using the caller class.
   */
  static PluginLogger getLogger(Class<?> topicClass) {
    return ClajVars.plugins.getLogger(topicClass);
  }

  /**
   * @return a new logger for this plugin with the specified {@code topic}.
   *         The plugin is automatically determined using the caller class.
   */
  static PluginLogger getLogger(String topic) {
    return ClajVars.plugins.getLogger(topic);
  }

  /** @return the folder where configuration files for this mod should go. */
  default Fi getConfigFolder() {
    return ClajVars.plugins.getConfigFolder(this);
  }

  /** @return a settings handle for this plugin of file {@code 'plugins/<plugin-name>/config.json'}. */
  default JsonSettings getConfig() {
    return ClajVars.plugins.getConfig(this);
  }

  /** @return the meta data of this plugin .*/
  default Plugins.PluginMeta getMeta() {
    return ClajVars.plugins.getMeta(this);
  }

  /** Called after all plugins have been created and commands have been registered. */
  default void init() {}

  /** Register any commands. */
  default void registerCommands(CommandHandler handler) {}

  /** Dispose any resources of the plugin. */
  default void dispose() {}
}
