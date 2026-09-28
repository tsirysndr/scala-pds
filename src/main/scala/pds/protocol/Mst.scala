package pds.protocol

import pds.crypto.{Encoding, Hash}
import scala.collection.mutable

/** Merkle search tree over `collection/rkey` keys: fanout 4, each key placed at
  * the layer given by the leading zero pairs of its SHA-256 hash, and
  * prefix-compressed on the wire.
  */
object Mst:
  final case class Tree(layer: Int, entries: Vector[Entry])

  enum Entry:
    case Leaf(key: String, value: Cid)
    case Sub(child: Child)

    def isLeaf: Boolean = this match
      case _: Leaf => true
      case _       => false

  enum Child:
    case Stored(cid: Cid)
    case Fresh(tree: Tree)

  val empty: Tree = Tree(0, Vector.empty)

  def keyHeight(key: String): Int =
    val digest = Hash.sha256(key)
    var zeros = 0
    var index = 0
    var done = false
    while index < digest.length && !done do
      val byte = digest(index) & 0xff
      var shift = 6
      while shift >= 0 && !done do
        if ((byte >>> shift) & 0x3) == 0 then zeros += 1 else done = true
        shift -= 2
      index += 1
    zeros

  def isValidKey(key: String): Boolean =
    key.split("/", -1) match
      case Array(collection, recordKey) => Syntax.isNsid(collection) && Syntax.isRecordKey(recordKey)
      case _                            => false

  def commonPrefix(left: Array[Byte], right: Array[Byte]): Int =
    var index = 0
    while index < left.length && index < right.length && left(index) == right(index) do index += 1
    index

  final class Store(reader: Cid => Option[Array[Byte]]):
    private val loaded = mutable.Map.empty[Cid, Tree]
    private val created = mutable.LinkedHashMap.empty[Cid, Array[Byte]]

    def blocks: Map[Cid, Array[Byte]] = created.toMap
    def createdCids: Set[Cid] = created.keySet.toSet

    def tree(cid: Cid): Either[String, Tree] =
      loaded.get(cid) match
        case Some(tree) => Right(tree)
        case None =>
          for
            bytes <- created.get(cid).orElse(reader(cid)).toRight(s"Missing MST block $cid")
            value <- Cbor.decode(bytes)
            tree <- decode(value)
          yield
            loaded.update(cid, tree)
            tree

    def resolve(child: Child): Either[String, Tree] = child match
      case Child.Stored(cid) => tree(cid)
      case Child.Fresh(tree) => Right(tree)

    def persist(tree: Tree): Either[String, Cid] =
      for
        entries <- tree.entries.foldLeft[Either[String, Vector[Entry]]](Right(Vector.empty)) {
          case (acc, Entry.Sub(Child.Fresh(child))) =>
            for
              list <- acc
              cid <- persist(child)
            yield list :+ Entry.Sub(Child.Stored(cid))
          case (acc, entry) => acc.map(_ :+ entry)
        }
        resolved = Tree(tree.layer, entries)
        bytes <- encode(resolved).map(Cbor.encode)
        cid = Cid.ofCbor(bytes)
      yield
        created.update(cid, bytes)
        loaded.update(cid, resolved)
        cid

    def layerOf(tree: Tree): Either[String, Int] =
      tree.entries.collectFirst { case Entry.Leaf(key, _) => keyHeight(key) } match
        case Some(layer) => Right(layer)
        case None =>
          tree.entries.collectFirst { case Entry.Sub(child) => child } match
            case None => Right(tree.layer)
            case Some(child) => resolve(child).flatMap(layerOf).map(_ + 1)

  def encode(tree: Tree): Either[String, Node] =
    val (left, rest) = tree.entries match
      case Entry.Sub(child) +: tail => (Some(child), tail)
      case all                      => (None, all)
    var previous = Array.emptyByteArray
    val serialized = rest.zipWithIndex.collect { case (Entry.Leaf(key, value), index) =>
      val bytes = Encoding.utf8(key)
      val prefix = commonPrefix(previous, bytes)
      previous = bytes
      val next = rest.lift(index + 1).collect { case Entry.Sub(child) => child }
      stored(next).map { link =>
        Node.Obj(Map(
          "p" -> Node.Integer(prefix.toLong),
          "k" -> Node.Bytes(bytes.drop(prefix)),
          "v" -> Node.Link(value),
          "t" -> link
        ))
      }
    }
    for
      entries <- serialized.foldLeft[Either[String, Vector[Node]]](Right(Vector.empty)) {
        (acc, item) => acc.flatMap(list => item.map(list :+ _))
      }
      head <- stored(left)
    yield Node.Obj(Map("l" -> head, "e" -> Node.Arr(entries)))

  private def stored(child: Option[Child]): Either[String, Node] = child match
    case None                    => Right(Node.Null)
    case Some(Child.Stored(cid)) => Right(Node.Link(cid))
    case Some(Child.Fresh(_))    => Left("MST child was not persisted before encoding")

  def decode(value: Node): Either[String, Tree] =
    val left = value("l").flatMap(_.asLink)
    for
      raw <- value("e").flatMap(_.asVector).toRight("MST node is missing its entry list")
      folded <- raw.foldLeft[Either[String, (Vector[Entry], Array[Byte])]](
        Right(left.map(cid => Entry.Sub(Child.Stored(cid))).toVector -> Array.emptyByteArray)
      ) { case (acc, item) =>
        for
          current <- acc
          (list, previous) = current
          prefix <- item("p").flatMap(_.asLong).filter(p => p >= 0 && p <= previous.length)
            .toRight("Invalid MST prefix length")
          suffix <- item("k").flatMap(_.asBytes).toRight("Invalid MST key suffix")
          link <- item("v").flatMap(_.asLink).toRight("Invalid MST value link")
          bytes = previous.take(prefix.toInt) ++ suffix
          key = Encoding.text(bytes)
          _ <- Either.cond(isValidKey(key), (), s"Invalid MST key")
          next = item("t").flatMap(_.asLink)
        yield ((list :+ Entry.Leaf(key, link)) ++ next.map(cid => Entry.Sub(Child.Stored(cid)))) -> bytes
      }
      entries = folded._1
      keys = entries.collect { case Entry.Leaf(key, _) => key }
      layer = keys.headOption.map(keyHeight).getOrElse(0)
      _ <- Either.cond(keys.forall(keyHeight(_) == layer), (), "MST node mixes layers")
      _ <- Either.cond(keys.sliding(2).forall(p => p.length < 2 || p(0) < p(1)), (),
        "MST node keys are not sorted")
    yield Tree(layer, entries)
