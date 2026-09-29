package pds.lexicon

import cats.effect.IO
import io.circe.Json
import java.net.{IDN, URLEncoder}
import org.xbill.DNS.{Lookup, TXTRecord, Type}
import pds.identity.{Net, Resolver}
import pds.protocol.{Node, Repository, Syntax}

/** Resolves a published Lexicon for an NSID: DNS names the authority, the DID
  * document names its repository and signing key, and the schema arrives as a
  * signed proof. There is no unsigned fallback and no parent-domain fallback,
  * so the only thing that can publish `com.example.foo.*` is the DID that
  * `_lexicon.foo.example.com` names.
  */
object Resolution:
  val maxProofBytes = 2 * 1024 * 1024
  private val collection = "com.atproto.lexicon.schema"

  def authorityName(nsid: String): Either[String, String] =
    if !Syntax.isNsid(nsid) then Left("Invalid NSID")
    else
      val authority = nsid.toLowerCase.split("\\.", -1).dropRight(1).reverse.mkString(".")
      val name = s"_lexicon.$authority"
      if name.length > 253 then Left("Lexicon authority name is too long") else Right(s"$name.")

  private def txt(name: String): IO[Vector[String]] =
    IO.blocking {
      val lookup = new Lookup(name, Type.TXT)
      Option(lookup.run()).toVector.flatten.collect { case record: TXTRecord =>
        record.getStrings.toArray.map(_.toString).mkString
      }
    }.handleError(_ => Vector.empty)

  private def authority(nsid: String): IO[Either[String, String]] =
    IO.fromEither(authorityName(nsid).left.map(new IllegalArgumentException(_)))
      .flatMap(txt)
      .map { records =>
        // A competing DID this implementation cannot use is still an ambiguity,
        // not licence to pick whichever record happens to be understood.
        records.collect { case value if value.startsWith("did=") => value.drop(4) }
          .distinct match
          case Vector(single) if Syntax.isDid(single) => Right(single)
          case _ => Left("The Lexicon authority could not be resolved")
      }
      .handleError(_ => Left("The Lexicon authority could not be resolved"))

  /** One document, authenticated end to end. */
  def resolve(net: Net, resolver: Resolver, nsid: String): IO[Either[String, Json]] =
    authority(nsid).flatMap {
      case Left(reason) => IO.pure(Left(reason))
      case Right(did) =>
        resolver.resolveDid(did).flatMap {
          case None => IO.pure(Left("The Lexicon authority's identity could not be resolved"))
          case Some(document) =>
            (document.signingKey, document.pdsEndpoint) match
              case (Some(key), Some(endpoint)) if document.id == did =>
                fetch(net, endpoint, did, nsid).map(_.flatMap { archive =>
                  Repository.proveRecord(archive, did, key, collection, nsid)
                    .left.map(_ => "The published Lexicon could not be verified")
                    .flatMap {
                      case (_, None) => Left("No Lexicon is published for that NSID")
                      case (_, Some((_, record))) =>
                        val json = Node.toJson(record)
                        if Admission.isSchemaRecord(nsid, json) then Right(json)
                        else Left("The published Lexicon record is not a schema")
                    }
                })
              case _ => IO.pure(Left("The Lexicon authority has no usable identity"))
        }
    }

  private def fetch(
      net: Net, endpoint: String, did: String, nsid: String
  ): IO[Either[String, Array[Byte]]] =
    val url = s"$endpoint/xrpc/com.atproto.sync.getRecord" +
      s"?did=${URLEncoder.encode(did, "UTF-8")}" +
      s"&collection=$collection&rkey=${URLEncoder.encode(nsid, "UTF-8")}"
    net.getBytes(url, maxProofBytes)
      .map(_.toRight("The published Lexicon could not be fetched"))
