package com.moveinsync.config.server.service;

import com.moveinsync.config.core.change.ChangeEvent;

/** Fans a committed event out to connected clients. Called only after the event is durable. */
public interface ChangePublisher {

    void publish(ChangeEvent event);
}
