package com.c0x12c.redlab.labctl.scenario

import kotlin.math.roundToInt

/**
 * Catalog of lab incidents. Spoiler warning: this file holds the answers. Try the exercises first.
 *
 * Each scenario draws random parameters, turns them into fault overrides per service, and carries
 * the ticket, the hints, the accepted answer and the explanation with its PromQL evidence.
 */
object ScenarioCatalog {

  val all: List<Scenario> = listOf(
    tenantSpike(),
    retryStorm(),
    poolHold(),
    nPlusOne(),
    cpuThrottle(),
    singleThreadCpu(),
    queueBeforeApp(),
    lbNoHealthy(),
    downstreamSlow(),
    cacheMiss(),
    clientTimeout(),
    consumerBacklog(),
    poisonMessage(),
    dbLock(),
    oom(),
    noisyNeighbor()
  )

  val byId: Map<String, Scenario> = all.associateBy { it.id }

  // ---------------------------------------------------------------- RED / load
  private fun tenantSpike() = Scenario(
    id = "tenant_spike",
    title = "One tenant's traffic surges",
    level = 1,
    topics = listOf("red", "load"),
    // Crawler rate calibrated to this machine: aim for about 70% of 2 Postgres cores on cache misses.
    params = { random, context ->
      val cost = context.productDetailSeconds ?: DEFAULT_PRODUCT_DETAIL_SECONDS
      val extra = (CRAWLER_CPU_BUDGET / cost * random.nextDouble(0.9, 1.1)).roundToInt().coerceIn(MIN_CRAWLER_RPS, MAX_CRAWLER_RPS)
      mapOf("extra" to extra, "tenant" to "partner-x")
    },
    faults = { p -> FaultSpec(services = mapOf("loadgen" to mapOf("extra_rps" to mapOf(p.string("tenant") to p.int("extra"))))) },
    decoy = 0.5,
    ticket = { p ->
      "Alert at ${p.startedAt}: API p99 crossed its threshold and /checkout is getting slow. The mobile team asks whether the backend is slow. " +
        "According to the deploy calendar, nothing changed in checkout-api today."
    },
    hints = listOf(
      "Start with RED at the LB and client layers: is the rate the same as before the incident?",
      "If the rate went up, do workload characterization: split the rate by tenant and by endpoint (RED dashboard, Client row).",
      "One tenant grew several times over and requests products that are not in the cache. Follow that load down to the DB pool and Postgres."
    ),
    answer = Answer(nature = setOf("load"), location = setOf("client"), cause = "tenant_spike", partial = setOf("cache_miss")),
    explain = { p ->
      "Tenant ${p.string("tenant")} sends about ${p.int("extra")} extra requests/s to /products, walking the whole 100,000-product catalog like a crawler. " +
        "Almost every one of its requests misses the cache and runs the heavy product_detail query, so the api's 4-connection pool and Postgres CPU " +
        "saturate; everyone's /checkout slows down because it waits on the same pool. Rate rises at every layer at once: the pattern " +
        "'rate and duration rise together, so it may be load'. Workload characterization (who causes the load) points at the tenant right away. " +
        "The lower cache hit ratio here is a consequence of a new workload, not a broken cache: compared with the cache-miss exercise, the API rate is flat. " +
        "Fix: rate limit per tenant, give the crawler its own endpoint, or scale if this traffic is legitimate."
    },
    evidence = listOf(
      "sum by (tenant) (rate(client_requests_total[1m]))",
      "sum(rate(envoy_http_downstream_rq_total{envoy_http_conn_manager_prefix=\"ingress\"}[1m]))",
      "db_pool_waiting{job=\"api\"}",
      "sum(rate(cache_requests_total{result=\"hit\"}[1m])) / sum(rate(cache_requests_total[1m]))"
    ),
    doc = "RED method in detail > Rate; Combining > Workload characterization"
  )

  private fun retryStorm() = Scenario(
    id = "retry_storm",
    title = "Retry storm",
    level = 2,
    topics = listOf("red", "layers"),
    params = { random, _ -> mapOf("err" to round2(random.nextDouble(0.6, 0.75)), "retries" to 4) },
    faults = { p -> FaultSpec(services = mapOf("pricing" to mapOf("error_rate" to p.double("err")), "loadgen" to mapOf("retries" to p.int("retries")))) },
    ticket = { p ->
      "At ${p.startedAt}, customer support reports many customers cannot place orders. The dashboard shows traffic into /checkout jumping, " +
        "and someone proposes scaling api out."
    },
    hints = listOf(
      "Compare the requests users really send (logical requests at the client) with the rate at the LB. Are they still equal?",
      "The 'Attempts vs user requests' panel in the Client row: how many attempts does one user request become?",
      "Where do the /checkout errors at api come from? Look at api's http_client_requests_total towards pricing and at pricing's own RED."
    ),
    answer = Answer(nature = setOf("amplified"), location = setOf("client", "pricing"), cause = "retry_storm", partial = setOf("downstream_slow")),
    explain = { p ->
      "pricing answers 503 for about ${p.percent("err")} of /price requests, so api answers 502 for /checkout. The client retries immediately, " +
        "up to ${p.int("retries")} times, with no backoff. The number of requests users really send is unchanged, but attempts (and the rate at the LB " +
        "and at api) multiply. This is 'rate up but not real load': scaling api fixes nothing. " +
        "Fix: retry with backoff and jitter, cap retries (a retry budget), and fix the error in pricing."
    },
    evidence = listOf(
      "sum(rate(client_attempts_total{endpoint=\"checkout\"}[1m])) / sum(rate(client_requests_total{endpoint=\"checkout\"}[1m]))",
      "sum by (outcome) (rate(http_client_requests_total{job=\"api\",target=\"pricing\"}[1m]))",
      "sum by (status) (rate(http_server_requests_total{job=\"pricing\"}[1m]))"
    ),
    doc = "RED at many layers > Why Rate differs; Linking RED and USE > Causal chain"
  )

  // ---------------------------------------------------------------- layers / pool
  private fun poolHold() = Scenario(
    id = "pool_hold",
    title = "Connection pool exhausted after a deploy",
    level = 2,
    topics = listOf("layers", "use", "little"),
    params = { random, _ -> mapOf("recommend_ms" to random.nextInt(200, 261)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("recommend_in_txn" to true), "pricing" to mapOf("recommend_ms" to p.int("recommend_ms")))) },
    marker = "Deploy checkout-api v2.31: product suggestions added to the checkout step",
    ticket = { p ->
      "At ${p.startedAt}, alert: /checkout p99 jumps and 503s start. The checkout team just deployed v2.31. " +
        "The DB team says 'Postgres is fine'. Who is right, and where is the problem?"
    },
    hints = listOf(
      "Compare 'DB as seen from api' (db_client_session_seconds) with 'DB as seen from Postgres' (exec time from pgobserver).",
      "Where is the gap? Look at the Software resources row of the USE dashboard: pool in use, waiting, acquire wait.",
      "Little's Law: the /checkout rate times the connection hold time, against pool_max = 4. In a /checkout trace, which call sits inside db.session?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api_db", "api"), cause = "pool_exhausted"),
    explain = { p ->
      val hold = p.int("recommend_ms") + BASE_CHECKOUT_HOLD_MS
      val holdSeconds = hold / MILLIS_PER_SECOND
      val need = CHECKOUTS_PER_SECOND * holdSeconds
      "v2.31 calls pricing /recommend (about ${p.int("recommend_ms")} ms) while still holding the connection with a transaction open. " +
        "The connection hold time per checkout goes from about 10 ms to about $hold ms. At about $CHECKOUTS_PER_SECOND checkouts/s, " +
        "Little's Law gives L = $CHECKOUTS_PER_SECOND x ${p.fixed(holdSeconds, 3)} ≈ ${p.fixed(need, 1)} connections, above the pool of 4. " +
        "The pool saturates: requests wait for a connection and get a 503 after 2 s. Postgres is not slow: exec time is flat and the connection count is low. " +
        "Fix: call /recommend outside the transaction, or asynchronously. Raising the pool only hides the problem and may hit max_connections when you scale."
    },
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le, op) (rate(db_client_session_seconds_bucket{job=\"api\"}[1m])))",
      "sum by (query) (rate(pg_query_exec_seconds_total[1m])) / sum by (query) (rate(pg_query_calls_total[1m]))",
      "db_pool_in_use{job=\"api\"}, db_pool_waiting{job=\"api\"}, db_pool_max{job=\"api\"}"
    ),
    doc = "End-to-end investigation example; Linking RED and USE > Little's Law"
  )

  private fun nPlusOne() = Scenario(
    id = "n_plus_one",
    title = "N+1 queries after an ORM change",
    level = 1,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("k" to random.nextInt(6, 11)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("n_plus_one" to p.int("k")))) },
    marker = "Deploy checkout-api v2.30: data layer moved to a new ORM",
    ticket = { p ->
      "At ${p.startedAt}, the DBA reports that the number of queries into Postgres multiplied and asks whether there is a marketing campaign. " +
        "Marketing says there is none."
    },
    hints = listOf(
      "Compare the rate at the API with the query rate at Postgres. Do they rise in the same proportion?",
      "Split the query rate by query name. Which query is new or jumped?",
      "Divide product_by_id queries per second by /checkout requests per second: how many queries does one checkout send?"
    ),
    answer = Answer(nature = setOf("amplified"), location = setOf("api_db", "api"), cause = "n_plus_one"),
    explain = { p ->
      val queries = (AVERAGE_BASKET * p.int("k")).roundToInt()
      "The new ORM loads prices one product at a time, ${p.int("k")} times per product (lazy loading). An average checkout of $AVERAGE_BASKET products " +
        "turns into about $queries queries instead of 1. The API rate is flat but the DB rate is up: the fan-out grew, not the users. " +
        "Each query's exec time inside Postgres stays small; checkout is slower because the round trips add up. " +
        "Fix: load in batches (WHERE id = ANY(...)) or eager loading."
    },
    evidence = listOf(
      "sum by (query) (rate(db_client_queries_total{job=\"api\"}[1m]))",
      "sum(rate(db_client_queries_total{job=\"api\",query=\"product_by_id\"}[1m])) / sum(rate(http_server_requests_total{job=\"api\",route=\"/checkout\"}[1m]))",
      "sum by (query) (rate(pg_query_calls_total[1m]))"
    ),
    doc = "RED at many layers > Why Rate differs (fan-out)"
  )

  // ---------------------------------------------------------------- USE / container, runtime
  private fun cpuThrottle() = Scenario(
    id = "cpu_throttle",
    title = "CPU throttling at the container",
    level = 2,
    topics = listOf("use", "container"),
    params = { random, _ -> mapOf("cpus" to round2(random.nextDouble(MIN_THROTTLE_CPUS, MAX_THROTTLE_CPUS))) },
    faults = { p -> FaultSpec(docker = mapOf("api" to mapOf("cpus" to p.double("cpus")))) },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, p99 rose on every endpoint, including /products which is normally very fast. The rate is normal. " +
        "A teammate looked at the host's CPU and said 'plenty of CPU left, it is not CPU'."
    },
    hints = listOf(
      "Rate flat, duration up on every route: suspect architecture. Do the LB and api rise together?",
      "Do USE per layer for api: host, container, runtime. Which layer's limit should utilization be measured against?",
      "Look at CPU usage against cgroup_cpu_limit_cores, and at api's share of throttled periods."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("container"), cause = "cpu_throttle"),
    explain = { p ->
      val quotaMs = (p.double("cpus") * CFS_PERIOD_MS).roundToInt()
      "The api container's CPU limit was lowered to ${p.double("cpus")} cores: each 100 ms period gets only $quotaMs ms of CPU. " +
        "When a burst of requests uses up the quota, the whole process waits for the next period. Host CPU barely changes. At the cgroup layer, " +
        "CPU use against the limit jumps several times over and the share of throttled periods climbs from 0 to 10% or more. " +
        "A 1-minute average still looks below 100% because the quota runs out inside single 100 ms periods, which is what the throttle counter shows. " +
        "This is the 'lower layer hides the upper one' case from USE at many layers."
    },
    evidence = listOf(
      "rate(cgroup_cpu_usage_seconds_total{job=\"api\"}[1m]) / cgroup_cpu_limit_cores{job=\"api\"}",
      "rate(cgroup_cpu_throttled_periods_total{job=\"api\"}[1m]) / rate(cgroup_cpu_periods_total{job=\"api\"}[1m])",
      "sum(rate(node_cpu_seconds_total{mode!=\"idle\"}[1m])) / count(node_cpu_seconds_total{mode=\"idle\"})"
    ),
    doc = "USE at many layers > One layer hides another"
  )

  private fun singleThreadCpu() = Scenario(
    id = "single_thread_cpu",
    title = "One core saturated by CPU work on the event loop",
    level = 2,
    topics = listOf("use", "runtime"),
    params = { random, _ -> mapOf("burn" to random.nextInt(18, 23)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("cpu_burn_ms" to p.int("burn")))) },
    marker = "Deploy checkout-api v2.32: the product page renders an extra detailed-reviews block",
    ticket = { p ->
      "At ${p.startedAt}, both /products and /checkout are slow although the rate is normal. " +
        "Someone proposes raising api's CPU limit from 2 to 4 cores."
    },
    hints = listOf(
      "How much CPU does the api container use against its 2-core limit? Is it throttled?",
      "api runs on one event loop thread: how many cores can it really use? Look at process CPU and event loop lag.",
      "In a /products trace, which span takes the time? Why does /checkout slow down too?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api"), cause = "single_thread_cpu"),
    explain = { p ->
      val cores = PRODUCTS_PER_SECOND * p.int("burn") / MILLIS_PER_SECOND
      "v2.32 adds about ${p.int("burn")} ms of CPU per /products, run on the event loop thread. At about $PRODUCTS_PER_SECOND requests/s " +
        "that is about ${p.fixed(cores, 2)} cores of CPU on a single thread. The event loop is close to saturated, so every other request " +
        "(including /checkout) waits: event loop lag rises. The container uses about 1 of its 2 cores and is not throttled, so raising the limit " +
        "to 4 cores does nothing. This is the 'single-threaded application' example in section 2.3.8 Load vs. Architecture."
    },
    evidence = listOf(
      "rate(process_cpu_seconds_total{job=\"api\"}[1m])",
      "histogram_quantile(0.99, sum by (le) (rate(event_loop_lag_seconds_bucket{job=\"api\"}[1m])))",
      "rate(cgroup_cpu_throttled_periods_total{job=\"api\"}[1m]) / rate(cgroup_cpu_periods_total{job=\"api\"}[1m])"
    ),
    doc = "USE method in detail > Pitfalls (total CPU hides one core)"
  )

  private fun queueBeforeApp() = Scenario(
    id = "queue_before_app",
    title = "Requests queue before the app accepts them",
    level = 2,
    topics = listOf("layers", "red"),
    params = { _, _ -> mapOf("limit" to 1) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("admission_limit" to p.int("limit")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, users complain the app is slow. The api team looks at APM and says their p99 is still normal, so it must be the network." },
    hints = listOf(
      "Put the LB (Envoy) p99 and the api p99 on the same chart.",
      "The gap between the two lines is time the request spends where? See the Software resources row of the USE dashboard.",
      "Look at app_admission_waiting and app_admission_wait_seconds."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("lb", "api"), cause = "queue_before_app"),
    explain = { p ->
      "The api's concurrency limit was set to ${p.int("limit")}: only one request at a time enters the handler, the rest wait ahead of it. " +
        "The duration api measures (APM) starts when the handler begins, so it stays flat. The LB measures from the moment it receives the request, " +
        "so it sees the wait too. The gap between LB latency and api latency is the queueing time: saturation showing up as duration."
    },
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le) (rate(envoy_http_downstream_rq_time_bucket{envoy_http_conn_manager_prefix=\"ingress\"}[1m]))) / 1000",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))",
      "app_admission_waiting{job=\"api\"}"
    ),
    doc = "RED at many layers > Why Duration differs"
  )

  private fun lbNoHealthy() = Scenario(
    id = "lb_no_healthy",
    title = "The LB answers 503 itself",
    level = 1,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("prob" to round2(random.nextDouble(0.45, 0.6))) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("health_fail_prob" to p.double("prob")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, users get scattered 503s. The api team insists their APM shows no errors at all." },
    hints = listOf(
      "Which layer shows the errors first? Compare 5xx at the client, at the LB and at api.",
      "Compare the 5xx the LB returns to clients with the 5xx api records itself. Who produces the difference?",
      "Look at Envoy's healthy upstream count and the request rate reaching api. What does api's /health return?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("lb", "api"), cause = "lb_no_healthy"),
    explain = { p ->
      "api's /health answers 503 with probability ${p.percent("prob")}. Envoy marks the backend unhealthy after 2 consecutive failures and, " +
        "with panic mode off, answers 503 'no healthy upstream' itself during those windows. Requests never reach api, so APM sees a lower rate, " +
        "not errors. This is 'errors the LB produces itself' from RED at many layers."
    },
    evidence = listOf(
      "sum(rate(envoy_http_downstream_rq_xx{envoy_response_code_class=\"5\",envoy_http_conn_manager_prefix=\"ingress\"}[1m]))",
      "sum(rate(http_server_requests_total{job=\"api\",status=~\"5..\"}[1m])) or vector(0)",
      "sum(rate(envoy_cluster_upstream_cx_none_healthy{envoy_cluster_name=\"api\"}[1m]))",
      "envoy_cluster_membership_healthy{envoy_cluster_name=\"api\"}",
      "sum(rate(http_server_requests_total{job=\"api\"}[1m]))"
    ),
    doc = "RED at many layers > Why Errors differ"
  )

  private fun downstreamSlow() = Scenario(
    id = "downstream_slow",
    title = "A slow downstream",
    level = 1,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("extra" to random.nextInt(250, 451), "frac" to round2(random.nextDouble(0.3, 0.6))) },
    faults = { p -> FaultSpec(services = mapOf("pricing" to mapOf("extra_latency_ms" to p.int("extra"), "slow_fraction" to p.double("frac")))) },
    decoy = 0.7,
    ticket = { p -> "At ${p.startedAt}, /checkout p99 is several times higher. /products is still normal." },
    hints = listOf(
      "On which route did duration rise? Did the rate change?",
      "Inside /checkout, which downstream rose with it: the DB, pricing or Kafka?",
      "Compare http_client_duration_seconds (api calling pricing) with pricing's own http_server_duration_seconds."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("pricing"), cause = "downstream_slow"),
    explain = { p ->
      "About ${p.percent("frac")} of pricing's /price requests get about ${p.int("extra")} ms of extra latency. api's /checkout duration rises by exactly " +
        "the pricing call span; pricing's own RED confirms the latency rose on its server side, so it is neither the network nor api's pool."
    },
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le, route) (rate(http_client_duration_seconds_bucket{job=\"api\"}[1m])))",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"pricing\"}[1m])))"
    ),
    doc = "RED at many layers > Compare two adjacent layers"
  )

  private fun cacheMiss() = Scenario(
    id = "cache_miss",
    title = "Cache misses push the load onto the DB",
    level = 2,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("ttl" to random.nextInt(100, 401)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("cache_ttl_ms" to p.int("ttl")))) },
    marker = "Config change: checkout-api applied a new configuration set",
    ticket = { p -> "At ${p.startedAt}, Postgres CPU is up and the DBA asks who is firing queries at the products table. API traffic is unchanged." },
    hints = listOf(
      "Which query grew in Postgres? Did the API rate grow with it?",
      "What does a /products request pass through before it reaches the DB?",
      "Look at the cache hit ratio."
    ),
    answer = Answer(nature = setOf("amplified", "arch"), location = setOf("cache", "api"), cause = "cache_miss"),
    explain = { p ->
      "The cache TTL was set to ${p.int("ttl")} ms, so almost every /products misses and runs the product_detail query (a scan over reviews). " +
        "The API rate is flat, the product_detail query rate at the DB climbs, and /products latency follows. " +
        "This is rate rising at a lower layer because of the cache, not because of users."
    },
    evidence = listOf(
      "sum(rate(cache_requests_total{result=\"hit\"}[1m])) / sum(rate(cache_requests_total[1m]))",
      "sum by (query) (rate(pg_query_calls_total[1m]))"
    ),
    doc = "RED at many layers > Why Rate differs (cache); Combining > Cache tuning"
  )

  private fun clientTimeout() = Scenario(
    id = "client_timeout",
    title = "Client timeout shorter than server latency",
    level = 3,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("timeout" to random.nextInt(150, 201), "extra" to random.nextInt(230, 321)) },
    faults = { p ->
      FaultSpec(
        services = mapOf(
          "loadgen" to mapOf("checkout_timeout_ms" to p.int("timeout")),
          "pricing" to mapOf("extra_latency_ms" to p.int("extra"), "slow_fraction" to 0.5)
        )
      )
    },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, the app reports 'timeout' errors when placing orders. The api team says no request failed: everything returned 200." },
    hints = listOf(
      "Compare errors at the client with errors at the LB and at api. At the client, which kind of error is it?",
      "Compare the server-side /checkout p99 with the timeout the client uses.",
      "How long does the server take to answer 200, and how long until the client gives up?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("client", "pricing"), cause = "client_timeout", partial = setOf("downstream_slow")),
    explain = { p ->
      "The client set a ${p.int("timeout")} ms timeout on /checkout, while half of the /price requests are about ${p.int("extra")} ms slower, " +
        "so the server-side p99 exceeds that timeout. The client gives up and records a timeout; the server finishes anyway and records a 200. " +
        "Timeouts disagree between layers: the client sees errors, the server sees success and wastes the work. " +
        "Note too that the LB p99 barely moves, because requests the client cancels midway are not recorded in Envoy's histogram. " +
        "Metrics are usually recorded when a request ends, so an abandoned request easily vanishes from the server-side numbers."
    },
    evidence = listOf(
      "sum by (outcome) (rate(client_requests_total{endpoint=\"checkout\"}[1m]))",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\",route=\"/checkout\"}[1m])))"
    ),
    doc = "RED at many layers > Why Errors differ (mismatched timeouts)"
  )

  // ---------------------------------------------------------------- async
  private fun consumerBacklog() = Scenario(
    id = "consumer_backlog",
    title = "The consumer cannot keep up",
    level = 2,
    topics = listOf("async", "load"),
    params = { random, _ -> mapOf("extra" to random.nextInt(170, 241)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("extra_events_rps" to p.int("extra")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, the reporting team says new orders take minutes to show up (normally seconds). The worker shows no errors." },
    hints = listOf(
      "The worker's RED: consume rate, errors, processing time. Did the processing time change?",
      "Compare the produce rate with the consume rate. How is lag moving over time?",
      "Split the produce rate by source."
    ),
    answer = Answer(nature = setOf("load"), location = setOf("worker"), cause = "consumer_backlog"),
    explain = { p ->
      "A backfill job pushes about ${p.int("extra")} extra events/s into the orders topic. The worker processes each partition sequentially, " +
        "about 25 ms per message, so its capacity is about 120 msg/s across 3 partitions. The produce rate exceeds that capacity: " +
        "processing time is flat and errors are zero, but lag in messages and in seconds grows steadily. " +
        "Lag is the consumer group's saturation; end-to-end latency is what users see."
    },
    evidence = listOf(
      "sum by (source) (rate(kafka_produced_total[1m]))",
      "sum(rate(kafka_consumed_total{outcome=\"ok\"}[1m]))",
      "max(kafka_consumer_lag_seconds)"
    ),
    doc = "RED at many layers > The async layer"
  )

  private fun poisonMessage() = Scenario(
    id = "poison_message",
    title = "Poison message",
    level = 3,
    topics = listOf("async"),
    params = { random, _ -> mapOf("nonce" to String.format("%06x", random.nextInt(NONCE_SPACE))) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("poison_nonce" to p.string("nonce")), "worker" to mapOf("fail_nonce" to p.string("nonce")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, some orders are never processed while others are fine. The worker's total throughput is almost unchanged." },
    hints = listOf(
      "Look at lag per partition instead of the total.",
      "Are the worker's errors and retries up? On which partition?",
      "Read the worker log: make logs S=worker"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("worker"), cause = "poison_message"),
    explain = { p ->
      "One bad event (currency 'XXX', nonce ${p.string("nonce")}) makes the worker fail; it retries every second and never skips the message. " +
        "Because each partition is processed in order, the whole partition holding it is blocked: exactly one partition's lag grows without end " +
        "while the others keep moving, so the total throughput barely changes. Fix: cap the retries, then send the message to a DLQ."
    },
    evidence = listOf(
      "kafka_consumer_lag_messages",
      "sum by (partition) (rate(kafka_consumer_retries_total[1m]))"
    ),
    doc = "RED at many layers > The async layer (poison message)"
  )

  // ---------------------------------------------------------------- DB
  private fun dbLock() = Scenario(
    id = "db_lock",
    title = "Lock contention on one hot row",
    level = 3,
    topics = listOf("use", "db", "layers"),
    params = { random, _ -> mapOf("hold" to random.nextInt(45, 59)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("hot_row" to true, "lock_hold_ms" to p.int("hold")))) },
    marker = "Deploy checkout-api v2.33: reserve inventory when placing an order",
    ticket = { p -> "At ${p.startedAt}, /checkout is slow with 503s. This time the DB team confirms that query execution time inside Postgres went up." },
    hints = listOf(
      "Which query's exec time rose inside Postgres?",
      "Exec time is up, but what is Postgres doing? Look at pg_lock_waiters and connections by state.",
      "Does every checkout update the same row?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("db"), cause = "db_lock", partial = setOf("pool_exhausted")),
    explain = { p ->
      "v2.33 makes every checkout update the same inventory row 'HOT-1' and then holds the transaction about ${p.int("hold")} ms longer. " +
        "Transactions queue on the row lock: update_inventory exec time inside Postgres rises (the lock wait counts as exec time), pg_lock_waiters > 0. " +
        "Because connections are held while waiting for the lock, api's pool drains too, but that is a consequence. " +
        "Unlike the pool exercise, here Postgres itself reports the higher exec time."
    },
    evidence = listOf(
      "sum by (query) (rate(pg_query_exec_seconds_total[1m])) / sum by (query) (rate(pg_query_calls_total[1m]))",
      "pg_lock_waiters",
      "pg_connections"
    ),
    doc = "USE method in detail > Software resources (mutex, lock); Investigation example (comparison)"
  )

  // ---------------------------------------------------------------- memory / host
  private fun oom() = Scenario(
    id = "oom",
    title = "Periodic OOM kill",
    level = 3,
    topics = listOf("use", "container"),
    params = { random, _ -> mapOf("leak" to random.nextInt(MIN_LEAK_KB, MAX_LEAK_KB + 1)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("leak_kb" to p.int("leak")))) },
    decoy = 0.5,
    ticket = { p -> "Since ${p.startedAt}, about once a minute there is a burst of 503s that lasts a few seconds and then clears. Nobody deployed anything." },
    hints = listOf(
      "During the errors, is api still receiving requests? Look at process_start_time_seconds (the restart count).",
      "What does the api container's memory look like right before each restart, against its limit?",
      "cgroup_memory_usage_bytes against cgroup_memory_limit_bytes for api."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("container", "api"), cause = "oom"),
    explain = { p ->
      "Each request keeps about ${p.int("leak")} KB for ever (a memory leak). At about 50 requests/s memory grows by a few MB per second until it " +
        "reaches the container's 512 MB limit: the kernel OOM-kills the process, Docker restarts api, and Envoy answers 503 while api is down. " +
        "These are errors at the cgroup layer (USE). The sawtooth memory curve and the restart count are the main evidence."
    },
    evidence = listOf(
      "cgroup_memory_usage_bytes{job=\"api\"} / cgroup_memory_limit_bytes{job=\"api\"}",
      "changes(process_start_time_seconds{job=\"api\"}[10m])",
      "increase(node_vmstat_oom_kill[10m])"
    ),
    doc = "USE at many layers > Container, cgroup"
  )

  private fun noisyNeighbor() = Scenario(
    id = "noisy_neighbor",
    title = "Noisy neighbour on the host",
    level = 2,
    topics = listOf("use", "host"),
    params = { _, context -> mapOf("burners" to maxOf(MIN_BURNERS, 2 * (context.cpuCount ?: DEFAULT_CPU_COUNT))) },
    faults = { p -> FaultSpec(services = mapOf("noisy" to mapOf("burners" to p.int("burners")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, every service is slower, including pricing and the worker. The rate is normal and nobody deployed anything." },
    hints = listOf(
      "When everything slows down at once, think of the shared resources.",
      "USE at the host / VM layer: CPU by mode, load against the CPU count, CPU PSI.",
      "Which container uses the most CPU? Compare cgroup_cpu_usage_seconds_total by job."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("host"), cause = "noisy_neighbor"),
    explain = { p ->
      "The 'noisy' container (a batch job sharing the machine, given a high CPU priority through cpu_shares) runs ${p.int("burners")} CPU-burning threads. " +
        "Host / VM CPU is near 100%, load is far above the CPU count, CPU PSI rises. Every service slows down because they all compete for CPU. " +
        "None of your services is throttled or failing: the cause sits in the shared layer."
    },
    evidence = listOf(
      "sum by (mode) (rate(node_cpu_seconds_total{mode!=\"idle\"}[1m])) / scalar(count(node_cpu_seconds_total{mode=\"idle\"}))",
      "node_load1 / scalar(count(node_cpu_seconds_total{mode=\"idle\"}))",
      "sum by (job) (rate(cgroup_cpu_usage_seconds_total[1m]))"
    ),
    doc = "USE at many layers > Hypervisor, host"
  )

  private fun round2(value: Double): Double = (value * HUNDREDTHS).roundToInt() / HUNDREDTHS

  private const val HUNDREDTHS = 100.0
  private const val MILLIS_PER_SECOND = 1_000.0
  private const val DEFAULT_PRODUCT_DETAIL_SECONDS = 0.012
  private const val CRAWLER_CPU_BUDGET = 1.4
  private const val MIN_CRAWLER_RPS = 40
  private const val MAX_CRAWLER_RPS = 200
  private const val BASE_CHECKOUT_HOLD_MS = 25
  private const val CHECKOUTS_PER_SECOND = 15
  private const val AVERAGE_BASKET = 2.5

  // A JVM needs a little more than the Python service did to keep answering at all under a tiny quota.
  private const val MIN_THROTTLE_CPUS = 0.25
  private const val MAX_THROTTLE_CPUS = 0.4
  private const val CFS_PERIOD_MS = 100
  private const val PRODUCTS_PER_SECOND = 35
  private const val NONCE_SPACE = 0x1000000
  private const val MIN_BURNERS = 4

  // api idles near 300 of its 512 MB, so at 50 requests/s this leak reaches the limit about once a minute.
  private const val MIN_LEAK_KB = 90
  private const val MAX_LEAK_KB = 130
  private const val DEFAULT_CPU_COUNT = 4
}
