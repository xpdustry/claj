# Copy Link and Join (CLaJ)
This system allows you to play with your friends, just by creating a room, copying the link and sending it to your friends. <br>
In fact it's pretty much the same thing as Hamachi, but in a Mindustry mod.

This is a bundled, reworked and optimized version of the [CLaJ server](https://github.com/xzxADIxzx/Copy-Link-and-Join) and the [xzxADIxzx's Scheme-Size mod](https://github.com/xzxADIxzx/Scheme-Size) (with only the CLaJ feature).

> [!IMPORTANT]
> This CLaJ version is not compatible with the [xzxADIxzx's](https://github.com/xzxADIxzx) one. <br>
> The protocol has been reworked and optimized and CLaJ links have also been changed to a more standard version.


## Mindustry v8 note
Mindustry v8 has been released, and many changes have been made. Mods must now make changes to be compatible with this version. <br>
The mod is not officially updated to this version, at this time, but it remains compatible with it.

To install the mod for Mindustry v8, just go to the mod browser, search for **'claj'**, then click ``View Releases``
and install the latest version named **'CLaJ for Mindustry v8'**. <br>
Or you can download the mod file from the [releases section](https://github.com/Xpdustry/claj/releases) of pre-releases versions and place it into the mod folder of your game.


## How to use
### Client
**First, if you don't have the mod yet, you can find it in the mod browser by searching for 'claj' and then installing it.**

Start and host a map as normal (or your campaign): ``Host Multiplayer Game`` **>** ``Host``. <br>
Then go to ``Manage CLaJ Room``, select a server (or add your own), now ``Create Room`` and wait for the room to be created, click ``Copy Link`` and send the copied link to your friends.

To join, it's simple, copy the link your friend sent you, open your game, go to ~~``Play`` **>** ``Join Game`` **>** ``Join via CLaJ``~~ ``Play`` **>** ``Join via CLaJ`` **>** ``Use Link``, paste the link and ``OK``.

Now, if all goods, you can play with your friends, so enjoy =).


### Server
To host a server, just run the command ``java -jar claj-server.jar <port>``, where ``<port>`` is the port for the server. <br>
Also don't forget to open the port in TCP and UDP mode on your end-point and redirect it to the host machine.

A CLaJ server doesn't need much memory and cpu, 256MB of memory and one core are enough, even at high traffic.<br>
To change the memory allocated to the server, change the command to ``java -Xms<memory> -Xmx<memory> -jar claj-server.jar <port>``, where ``<memory>`` is the memory allocated to the server *(e.g. 256m for 256 MB of ram)*.

> [!IMPORTANT]
> Please note that if you plan to make a public server, CLaJ servers are high bandwidth consumers, as they act as a relay. For an average server, around 500GB up/down of consumption per month and around 1MB/s of constant network usage.
>
> Also, you can create a Pull-Request to add your server to the public server list (in [public-servers.hjson](https://github.com/xpdustry/claj/blob/main/public-servers.hjson)).


## How it works
CLaJ is a system like [Hamachi](https://vpn.net/), that allows you to create a room and share the link to your friends. This way, they can connect to you as if they were on your private network.

The only differences are that Hamachi requires account creation and therefore the collection of personal information, etc., while CLaJ does not. And CLaJ is directly integrated into Mindustry and optimized for it, which makes it easier to use compared to Hamachi, which needs to stay in parallel of the game.

On the host player's side, it's server never receives packets from people connected via CLaJ, the work is done by the CLaJ Proxy which simply run the server's callbacks.


## How to build
Pre-build releases can be found in the [releases section](https://github.com/Xpdustry/claj/releases), but if you want to build the project yourself, follow the steps above.

To build the client version, simply run ``./gradlew client:build``. The jar file will be located in the root directory and named ``claj-client.jar``. <br>
And to build the Mindustry v8 compatible version, run ``./gradlew client:buildV8``. The jar file will be located at the same place and named ``claj-client-v8.jar``.

To build the server version, simply run ``./gradlew server:build``. The jar file will be located in the root directory and named ``claj-server.jar``.

You can also run a test server by running ``./gradlew server:run``. It will be hosted on port ``7000``.


## Modding
The CLaJ server can be modded using plugins that are located in the ``plugins/`` directory, on your server. <br>
They work the same way as [Mindustry mods](https://mindustrygame.github.io/wiki/modding/2-plugins/), but only handles Java ones (not json and js) and doesn't handles sprites, icon, bundles, and others things designed for client-side.

The plugin metadata file can therefore only be ``plugin.json`` or ``plugin.hjson``, and some properties are removed. <br>
Such as ``java`` because these can only be Java plugins, or ``texturescale`` because there is no texture handling on servers.

Supported plugin properties: 
* ``name`` *[required]*: The plugin name, in lower case with no spaces (use '`-`'instead) and color formatting.
* ``displayName`` *[optional]*: The plugin name used for logging, etc. In any case you want, with spaces, colors, etc.
* ``author`` *[optional]*: The plugin's author(s).
* ``description`` *[optional]*: A short description of what the plugin does.
* ``version`` *[optional]*: The plugin's version.
* ``repo`` *[optional]*: The plugin's GitHub repository, formatted like that: `username/projectname`.
* ``main`` *[required]*: The plugin's fully qualified main class. Must implements ``com.xpdustry.claj.server.plugin.Plugin``.
* ``dependencies`` *[optional]*: The list of required dependencies. ([Further information](https://mindustrygame.github.io/wiki/modding/1-modding/#dependencies))
* ``softDependencies`` *[optional]*: The list of optional dependencies.


## Developers
CLaJ is not only compatible for Mindustry. <br>
In fact, this system doesn't care about what's transferred between the server, clients, and host. Only communications using the CLaJ protocol are important.

The project is designed to be modular, and is divided into several modules:
* ``common``: Contains the CLaJ protocol packets, packet handling, enums and some utilities.
* ``api``: Provides the client-side core layer, aka the proxy, handling the CLaJ logic with easy methods.
* ``client``: The Mindustry CLaJ implementation, also providing UI to manages CLaJ.
* ``server``: The relay server, managing packet redirection between room host and clients.

To integrate CLaJ into another game or application, the only requirements is to implement the [``ClajProvider``](https://github.com/xpdustry/claj/blob/main/api/src/com/xpdustry/claj/api/ClajProvider.java) interface, and call methods from the [``Claj``](https://github.com/xpdustry/claj/blob/main/api/src/com/xpdustry/claj/api/Claj.java) class.

CLaJ uses the [Arc framework](https://github.com/Anuken/Arc/) for its optimized utilities, but it's also a very versatile framework (containing graphics, audio and keyboard input management, etc). <br>
So to avoid including the entire framework, you can use [arc-lite](https://github.com/xpdustry/arc-lite) (which is a lightweight version that removes all client-related elements) with the [gradle shadow plugin](https://github.com/GradleUp/shadow), to remove anything not used by your project.
