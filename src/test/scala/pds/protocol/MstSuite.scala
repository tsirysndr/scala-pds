package pds.protocol

import pds.crypto.Encoding
import scala.collection.mutable
import scala.util.Random

class MstSuite extends munit.FunSuite:
  private def store() = new Mst.Store(_ => None)

  private def build(keys: Seq[String]): (Mst.Store, Cid) =
    val blocks = store()
    val tree = keys.foldLeft(Mst.empty) { (tree, key) =>
      MstOps.put(blocks, tree, key, Cid.ofRaw(Encoding.utf8(key))).fold(fail(_), identity)
    }
    (blocks, blocks.persist(tree).fold(fail(_), identity))

  private def key(index: Int): String = f"com.example.record/3jqfcqzm3f$index%06d"

  test("key heights match the interop fixtures") {
    Fixtures.json("mst/key_heights.json").foreach { fixture =>
      val value = fixture.hcursor.get[String]("key").toOption.get
      assertEquals(Mst.keyHeight(value), fixture.hcursor.get[Int]("height").toOption.get, value)
    }
  }

  test("common prefix lengths match the interop fixtures") {
    Fixtures.json("mst/common_prefix.json").foreach { fixture =>
      val left = Encoding.utf8(fixture.hcursor.get[String]("left").toOption.get)
      val right = Encoding.utf8(fixture.hcursor.get[String]("right").toOption.get)
      assertEquals(Mst.commonPrefix(left, right), fixture.hcursor.get[Int]("len").toOption.get)
    }
  }

  test("the empty tree is a single node with no entries and a stable CID") {
    val blocks = store()
    val cid = blocks.persist(Mst.empty).fold(fail(_), identity)
    assertEquals(Cbor.decode(blocks.blocks(cid)),
      Right(Node.Obj(Map("l" -> Node.Null, "e" -> Node.Arr(Vector.empty)))))
    assertEquals(cid.toString, "bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm")
  }

  test("the root CID does not depend on insertion order") {
    val keys = (0 until 250).map(key)
    val roots = (1 to 4).map(seed => build(new Random(seed).shuffle(keys)).swap._1.toString)
    assertEquals(roots.distinct.length, 1)
  }

  test("every inserted record is retrievable in sorted order") {
    val keys = (0 until 500).map(key)
    val (blocks, root) = build(new Random(7).shuffle(keys))
    val tree = blocks.tree(root).fold(fail(_), identity)
    val listed = MstOps.entries(blocks, tree).fold(fail(_), identity)
    assertEquals(listed.map(_._1), keys.sorted.toVector)
    keys.foreach { value =>
      assertEquals(MstOps.get(blocks, tree, value), Right(Some(Cid.ofRaw(Encoding.utf8(value)))))
    }
    assertEquals(MstOps.get(blocks, tree, key(9999)), Right(None))
  }

  test("deleting every record returns the empty root") {
    val keys = (0 until 300).map(key)
    val (blocks, root) = build(keys)
    val tree = blocks.tree(root).fold(fail(_), identity)
    val emptied = new Random(3).shuffle(keys).foldLeft(tree) { (current, value) =>
      MstOps.delete(blocks, current, value).fold(fail(_), identity)
    }
    assertEquals(blocks.persist(emptied).fold(fail(_), identity).toString,
      "bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm")
  }

  test("interleaved writes and deletes track a reference map") {
    val blocks = store()
    val random = new Random(11)
    val reference = mutable.Map.empty[String, Cid]
    var tree = Mst.empty
    (0 until 1200).foreach { step =>
      val candidate = key(random.nextInt(200))
      if reference.contains(candidate) && random.nextInt(3) == 0 then
        tree = MstOps.delete(blocks, tree, candidate).fold(fail(_), identity)
        reference.remove(candidate)
      else
        val value = Cid.ofRaw(Encoding.utf8(s"$candidate/$step"))
        tree = MstOps.put(blocks, tree, candidate, value).fold(fail(_), identity)
        reference.update(candidate, value)
    }
    val root = blocks.persist(tree).fold(fail(_), identity)
    val loaded = blocks.tree(root).fold(fail(_), identity)
    assertEquals(MstOps.entries(blocks, loaded).fold(fail(_), identity),
      reference.toVector.sortBy(_._1))
  }

  test("a tree reloaded from its blocks has the same contents") {
    val keys = (0 until 400).map(key)
    val (blocks, root) = build(keys)
    val exported = blocks.blocks
    val reader = new Mst.Store(cid => exported.get(cid))
    val tree = reader.tree(root).fold(fail(_), identity)
    assertEquals(MstOps.entries(reader, tree).fold(fail(_), identity).map(_._1), keys.sorted.toVector)
    assertEquals(MstOps.nodeCids(reader, root).fold(fail(_), identity).distinct.length,
      MstOps.nodeCids(reader, root).fold(fail(_), identity).length)
  }

  test("deleting an absent record fails instead of rewriting the tree") {
    val (blocks, root) = build((0 until 20).map(key))
    val tree = blocks.tree(root).fold(fail(_), identity)
    assert(MstOps.delete(blocks, tree, key(999)).isLeft)
  }

  test("keys outside the collection/rkey shape are rejected") {
    val blocks = store()
    List("no-slash", "com.example.record/", "/rkey", "Invalid NSID/rkey",
      "com.example.record/a/b", ""
    ).foreach { value =>
      assert(MstOps.put(blocks, Mst.empty, value, Cid.ofRaw(Array(1.toByte))).isLeft, value)
    }
  }

  test("nodes that mix layers or sort keys wrongly are rejected on decode") {
    def leaf(key: String) = Node.Obj(Map(
      "p" -> Node.Integer(0L), "k" -> Node.Bytes(Encoding.utf8(key)),
      "v" -> Node.Link(Cid.ofRaw(Encoding.utf8(key))), "t" -> Node.Null))
    val unsorted = Node.Obj(Map("l" -> Node.Null,
      "e" -> Node.Arr(Vector(leaf("com.example.record/bbb"), leaf("com.example.record/aaa")))))
    assert(Mst.decode(unsorted).isLeft)
    val mixed = Node.Obj(Map("l" -> Node.Null,
      "e" -> Node.Arr(Vector(leaf("com.example.record/self"), leaf("blue/blue")))))
    assert(Mst.decode(mixed).isLeft || Mst.keyHeight("com.example.record/self") == Mst.keyHeight("blue/blue"))
    assert(Mst.decode(Node.Obj(Map("l" -> Node.Null))).isLeft)
  }
