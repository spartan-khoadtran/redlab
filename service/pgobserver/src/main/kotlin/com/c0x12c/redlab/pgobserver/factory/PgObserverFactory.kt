package com.c0x12c.redlab.pgobserver.factory

import com.c0x12c.redlab.faults.Faults
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class PgObserverFactory {

  // No faults of its own; the endpoint exists so the controller's status check treats it like every service.
  @Singleton
  fun faults(): Faults = Faults(emptyMap())
}
