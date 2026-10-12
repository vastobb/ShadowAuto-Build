package com.phantom.automation.shizuku;

interface IPhantomService {
    /** 0=idle, 1=running, negative=error. Never throws for state queries. */
    int getState();

    /** Start the privileged RPC daemon on loopback. Returns 0 on success. */
    int startDaemon(int port);

    /** Stop the daemon and release displays/encoders. */
    void stopDaemon();
}
