package com.c0x12c.redlab.shared.exception

// Wire shape of an error body, built from a ClientException at the edge.
data class ErrorResponse(
  val code: String,
  val message: String,
  val status: Int
)
