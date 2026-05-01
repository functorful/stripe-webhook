package com.functorful.stripewebhook;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.time.Clock;

/**
 * Provides the system UTC {@link Clock} as a Micronaut singleton so that
 * tests can register an alternative bean (e.g. a fixed clock) to control
 * timestamp tolerance assertions deterministically.
 */
@Factory
public class ClockFactory {

    @Singleton
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
