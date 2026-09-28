package pds.protocol

import java.util.concurrent.atomic.AtomicLong

/** Monotonic timestamp identifiers: 53 bits of microseconds and a 10-bit
  * clock identifier, base32-sortable. Generation never repeats or regresses
  * within a process.
  */
object Tid:
  private val alphabet = "234567abcdefghijklmnopqrstuvwxyz"
  private val clockId = scala.util.Random.nextInt(1024).toLong
  private val last = new AtomicLong(0L)

  def next(): String =
    val micros = last.updateAndGet { previous =>
      val now = System.currentTimeMillis() * 1000L
      if now > previous then now else previous + 1L
    }
    encode(micros, clockId)

  def encode(micros: Long, clock: Long): String =
    val value = ((micros & ((1L << 53) - 1)) << 10) | (clock & 0x3ff)
    val chars = new Array[Char](13)
    var remaining = value
    var index = 12
    while index >= 0 do
      chars(index) = alphabet((remaining & 0x1f).toInt)
      remaining >>>= 5
      index -= 1
    new String(chars)

  def decode(value: String): Option[Long] =
    if !Syntax.isTid(value) then None
    else Some(value.foldLeft(0L)((acc, c) => (acc << 5) | alphabet.indexOf(c).toLong) >>> 10)
