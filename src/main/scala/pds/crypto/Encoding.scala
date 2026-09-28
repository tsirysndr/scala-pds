package pds.crypto

import java.nio.charset.StandardCharsets
import java.util.Base64

object Encoding:
  def utf8(value: String): Array[Byte] = value.getBytes(StandardCharsets.UTF_8)
  def text(bytes: Array[Byte]): String = new String(bytes, StandardCharsets.UTF_8)

  private val b64Encoder = Base64.getUrlEncoder.withoutPadding
  private val b64Decoder = Base64.getUrlDecoder

  def b64(bytes: Array[Byte]): String = b64Encoder.encodeToString(bytes)
  def b64(value: String): String = b64(utf8(value))

  def unb64(value: String): Option[Array[Byte]] =
    if value.exists(c => c == '+' || c == '/' || c == '=' || c == '\n') then None
    else
      try Some(b64Decoder.decode(value))
      catch case _: IllegalArgumentException => None

  def b64Std(bytes: Array[Byte]): String = Base64.getEncoder.encodeToString(bytes)
  def unb64Std(value: String): Option[Array[Byte]] =
    try Some(Base64.getMimeDecoder.decode(value))
    catch case _: IllegalArgumentException => None

  private val base32Alphabet = "abcdefghijklmnopqrstuvwxyz234567"

  def base32(bytes: Array[Byte]): String =
    val out = new StringBuilder((bytes.length * 8 + 4) / 5)
    var buffer = 0L
    var bits = 0
    bytes.foreach { byte =>
      buffer = (buffer << 8) | (byte & 0xffL)
      bits += 8
      while bits >= 5 do
        bits -= 5
        out.append(base32Alphabet(((buffer >>> bits) & 0x1f).toInt))
    }
    if bits > 0 then out.append(base32Alphabet(((buffer << (5 - bits)) & 0x1f).toInt))
    out.toString

  def unbase32(value: String): Option[Array[Byte]] =
    val out = new java.io.ByteArrayOutputStream(value.length * 5 / 8 + 1)
    var buffer = 0L
    var bits = 0
    var valid = value.nonEmpty
    value.foreach { c =>
      val index = base32Alphabet.indexOf(c.toLower)
      if index < 0 then valid = false
      else
        buffer = (buffer << 5) | index.toLong
        bits += 5
        if bits >= 8 then
          bits -= 8
          out.write(((buffer >>> bits) & 0xff).toInt)
    }
    if valid && (buffer & ((1L << bits) - 1)) == 0 then Some(out.toByteArray) else None

  def hex(bytes: Array[Byte]): String =
    val out = new StringBuilder(bytes.length * 2)
    bytes.foreach(b => out.append(f"${b & 0xff}%02x"))
    out.toString

  def unhex(value: String): Option[Array[Byte]] =
    if value.length % 2 != 0 || !value.forall(c => "0123456789abcdefABCDEF".contains(c)) then None
    else Some(value.grouped(2).map(pair => Integer.parseInt(pair, 16).toByte).toArray)

  def unsigned(value: BigInt, width: Int): Array[Byte] =
    val bytes = value.toByteArray.dropWhile(_ == 0)
    require(bytes.length <= width, "scalar does not fit the field width")
    Array.fill(width - bytes.length)(0.toByte) ++ bytes

  private val base58Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

  def base58(bytes: Array[Byte]): String =
    if bytes.isEmpty then ""
    else
      val leadingZeros = bytes.takeWhile(_ == 0).length
      var value = BigInt(1, bytes)
      val digits = new StringBuilder
      while value > 0 do
        val (quotient, remainder) = value /% 58
        digits.append(base58Alphabet(remainder.toInt))
        value = quotient
      ("1" * leadingZeros) + digits.reverse.toString

  def unbase58(value: String): Option[Array[Byte]] =
    if value.isEmpty then None
    else
      var number = BigInt(0)
      var valid = true
      value.foreach { c =>
        val index = base58Alphabet.indexOf(c)
        if index < 0 then valid = false else number = number * 58 + index
      }
      if !valid then None
      else
        val leadingZeros = value.takeWhile(_ == '1').length
        val body = if number == 0 then Array.emptyByteArray else number.toByteArray.dropWhile(_ == 0)
        Some(Array.fill(leadingZeros)(0.toByte) ++ body)
