package com.c0x12c.redlab.labctl.scenario

/** The three multiple-choice questions a learner answers, with the stable keys the catalog uses. */
object Answers {
  val NATURES: List<Pair<String, String>> = listOf(
    "load" to "Real load increase: demand from users or an external source went up",
    "arch" to "Not load: the system itself got slower or started failing (code, config, resource)",
    "amplified" to "Rate rose at one layer but it is not real demand (retry, fan-out, cache miss)"
  )

  val LOCATIONS: List<Pair<String, String>> = listOf(
    "client" to "Client side / the load source",
    "lb" to "Load balancer, or a queue before the app accepts the request",
    "api" to "Inside api (code, runtime, api's software resources)",
    "api_db" to "The api -> Postgres leg, on the DB client side (pool, query count)",
    "db" to "Inside Postgres",
    "pricing" to "The pricing service",
    "cache" to "Redis / cache",
    "worker" to "The Kafka consumer (worker)",
    "container" to "The api container / cgroup",
    "host" to "The shared host / VM"
  )

  val CAUSES: List<Pair<String, String>> = listOf(
    "tenant_spike" to "Traffic surge from one client group / tenant",
    "retry_storm" to "Retry storm: client retries multiply the requests",
    "pool_exhausted" to "Connection pool exhausted because connections are held too long",
    "n_plus_one" to "N+1 queries: each request sends far too many queries",
    "cpu_throttle" to "CPU throttling from the container's CPU limit",
    "single_thread_cpu" to "One core saturated: CPU-bound code on a single-threaded event loop",
    "queue_before_app" to "Requests queue before the app accepts them (concurrency / workers too low)",
    "lb_no_healthy" to "The load balancer answers 503 itself because no backend is healthy",
    "downstream_slow" to "A downstream service is slow",
    "cache_miss" to "Cache misses rose, load shifted to the DB",
    "client_timeout" to "The client's timeout is shorter than the server's latency",
    "consumer_backlog" to "The consumer cannot keep up with the producer, backlog grows",
    "poison_message" to "A poison message blocks one partition",
    "db_lock" to "Lock contention in Postgres",
    "oom" to "Memory limit hit: OOM kill, then restart",
    "noisy_neighbor" to "Another process takes the host's CPU"
  )

  val DECOYS: List<String> = listOf(
    "Deploy worker v1.8: logging library update",
    "Deploy pricing v3.4: log format change",
    "Feature flag: promo banner enabled on web",
    "Deploy loadgen-ios v5.2: display bug fix"
  )

  val TOPICS: List<String> = listOf("red", "load", "layers", "use", "container", "runtime", "db", "async", "host", "little")

  fun label(options: List<Pair<String, String>>, key: String): String = options.first { it.first == key }.second
}
