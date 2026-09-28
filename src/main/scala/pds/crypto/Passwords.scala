package pds.crypto

import org.bouncycastle.crypto.generators.SCrypt

object Passwords:
  private val cpuCost = 1 << 14
  private val memoryCost = 8
  private val parallelism = 1
  private val length = 32
  private val prefix = "$scrypt$"

  def hash(password: String): String =
    encode(Hash.randomBytes(16), cpuCost, memoryCost, parallelism, password)

  private def encode(salt: Array[Byte], n: Int, r: Int, p: Int, password: String): String =
    val derived = SCrypt.generate(Encoding.utf8(password), salt, n, r, p, length)
    s"$prefix$n$$$r$$$p$$${Encoding.b64(salt)}$$${Encoding.b64(derived)}"

  def matches(password: String, stored: String): Boolean =
    parse(stored).exists { case (n, r, p, salt, expected) =>
      val derived = SCrypt.generate(Encoding.utf8(password), salt, n, r, p, expected.length)
      Hash.constantTimeEquals(derived, expected)
    }

  private def parse(stored: String): Option[(Int, Int, Int, Array[Byte], Array[Byte])] =
    if !stored.startsWith(prefix) then None
    else
      stored.drop(prefix.length).split('$') match
        case Array(n, r, p, salt, digest) =>
          for
            n <- n.toIntOption.filter(value => value >= 1024 && value <= (1 << 20))
            r <- r.toIntOption.filter(value => value >= 1 && value <= 32)
            p <- p.toIntOption.filter(value => value >= 1 && value <= 16)
            salt <- Encoding.unb64(salt).filter(_.length >= 8)
            digest <- Encoding.unb64(digest).filter(_.length >= 16)
          yield (n, r, p, salt, digest)
        case _ => None

  /** Equalizes login cost for unknown accounts. */
  lazy val dummy: String = hash(Hash.token())
