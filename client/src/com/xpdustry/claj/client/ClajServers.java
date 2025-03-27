package com.xpdustry.claj.client;

import arc.Core;
import arc.struct.ArrayMap;
import arc.util.Http;
import arc.util.serialization.Jval;


public class ClajServers {
  public static final String publicServersLink = 
      "https://github.com/xpdustry/claj-v2/blob/main/public-servers.hjson?raw=true";
  public static final ArrayMap<String, String> online = new ArrayMap<>(),
                                               custom = new ArrayMap<>();
  
  public static synchronized void refreshOnline() {
    online.clear();
    // Public list
    Http.get(publicServersLink)
        .error(error -> mindustry.Vars.ui.showException("@claj.servers.fetch-failed", error))
        .block(result -> Jval.read(result.getResultAsString())
                             .asObject()
                             .forEach(e -> online.put(e.key, e.value.asString()))
    );
    //online.put("Chaotic Neutral", "n3.xpdustry.com:7025");
  }
  
  @SuppressWarnings("unchecked")
  public static void loadCustom() {
    custom.clear();
    custom.putAll(Core.settings.getJson("claj-custom-servers", ArrayMap.class, String.class, ArrayMap::new));
  }
  
  public static void saveCustom() {
    Core.settings.putJson("claj-custom-servers", String.class, custom);
  }
}
