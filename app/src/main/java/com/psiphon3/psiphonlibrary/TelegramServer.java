package com.psiphon3.psiphonlibrary;

/** One selectable upstream server for the Telegram screen. */
public final class TelegramServer {

    public final String id;
    public final String name;
    public final String host;
    public final int port;

    public TelegramServer(String id, String name, String host, int port) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.host = host == null ? "" : host;
        this.port = port;
    }
}
