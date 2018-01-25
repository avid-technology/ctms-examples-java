package com.avid.ctms.examples.tools.common;

import java.util.function.*;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2017-05-04
 * Time: 08:47
 * Project: CTMS
 */

/**
 * Dedicated interface to indicate termination callbacks/continuations.
 */
@FunctionalInterface
public interface Terminator<T, E extends Throwable> extends BiConsumer<T, E> {
    void terminate(T message, E exception);

    default void accept(T message, E exception) {
        terminate(message, exception);
    }
}