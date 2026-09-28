package pds.protocol

import pds.crypto.{Curve, PrivateKey}

class RepositorySuite extends munit.FunSuite:
  private val did = "did:plc:7iza6de2dwap2sbkpav7c6c6"
  private val key = PrivateKey.generate(Curve.K256)

  private def record(text: String): Node = Node.Obj(Map(
    "$type" -> Node.Text("app.bsky.feed.post"),
    "text" -> Node.Text(text),
    "createdAt" -> Node.Text("2026-01-01T00:00:00.000Z")
  ))

  private def genesis = Repository.create(did, Tid.next(), key).fold(fail(_), identity)

  test("the genesis commit is signed, versioned and holds an empty tree") {
    val created = genesis
    assertEquals(created.commit.version, 3)
    assertEquals(created.commit.prev, None)
    assert(created.commit.verify(key.publicKey))
    assert(!created.commit.verify(PrivateKey.generate(Curve.K256).publicKey))
    assertEquals(Commit.decode(Cbor.decode(created.commit.bytes).toOption.get).map(_.cid),
      Right(created.commit.cid))
    assertEquals(created.root.toString, "bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm")
  }

  test("creates, updates and deletes produce ops, blocks and a signed head") {
    val created = genesis
    val blocks = scala.collection.mutable.Map.from(created.blocks)
    def reader(cid: Cid) = blocks.get(cid)

    val first = Repository.apply(did, created.commit, Tid.next(), key,
      Vector(Write.Create("app.bsky.feed.post", "3jqfcqzm3fo2j", record("hello"))), reader)
      .fold(fail(_), identity)
    blocks ++= first.blocks
    assertEquals(first.ops.map(_.action), Vector("create"))
    assertEquals(first.commit.prev, Some(created.commit.cid))
    assert(first.commit.verify(key.publicKey))
    assertEquals(Repository.verify(first.commit, key.publicKey, reader), Right(()))

    val updated = Repository.apply(did, first.commit, Tid.next(), key,
      Vector(Write.Update("app.bsky.feed.post", "3jqfcqzm3fo2j", record("edited"))), reader)
      .fold(fail(_), identity)
    blocks ++= updated.blocks
    assertEquals(updated.ops.head.action, "update")
    assertEquals(updated.ops.head.prev, first.ops.head.cid)

    val deleted = Repository.apply(did, updated.commit, Tid.next(), key,
      Vector(Write.Delete("app.bsky.feed.post", "3jqfcqzm3fo2j")), reader).fold(fail(_), identity)
    blocks ++= deleted.blocks
    assertEquals(deleted.ops.map(_.action), Vector("delete"))
    assertEquals(deleted.root, created.root)
  }

  test("a commit rejects duplicate paths, missing records and existing keys") {
    val created = genesis
    val blocks = scala.collection.mutable.Map.from(created.blocks)
    def reader(cid: Cid) = blocks.get(cid)
    val post = Write.Create("app.bsky.feed.post", "3jqfcqzm3fo2j", record("hello"))
    val first = Repository.apply(did, created.commit, Tid.next(), key, Vector(post), reader)
      .fold(fail(_), identity)
    blocks ++= first.blocks

    assert(Repository.apply(did, created.commit, Tid.next(), key, Vector.empty, reader).isLeft)
    assert(Repository.apply(did, created.commit, Tid.next(), key, Vector(post, post), reader).isLeft)
    assert(Repository.apply(did, first.commit, Tid.next(), key, Vector(post), reader).isLeft)
    assert(Repository.apply(did, first.commit, Tid.next(), key,
      Vector(Write.Delete("app.bsky.feed.post", "missing")), reader).isLeft)
    assert(Repository.apply(did, first.commit, Tid.next(), key,
      Vector(Write.Create("not an nsid", "rkey", record("x"))), reader).isLeft)
  }

  test("oversized records are refused before they reach the tree") {
    val created = genesis
    val huge = Node.Obj(Map("$type" -> Node.Text("app.bsky.feed.post"),
      "text" -> Node.Text("x" * (Repository.maxRecordSize + 1))))
    assert(Repository.apply(did, created.commit, Tid.next(), key,
      Vector(Write.Create("app.bsky.feed.post", "3jqfcqzm3fo2j", huge)),
      created.blocks.get).isLeft)
  }

  test("a CAR archive round-trips the commit, tree and records") {
    val created = genesis
    val blocks = scala.collection.mutable.Map.from(created.blocks)
    val writes = (0 until 60).map(index =>
      Write.Create("app.bsky.feed.post", f"3jqfcqzm3f$index%04d", record(s"post $index"))).toVector
    val head = Repository.apply(did, created.commit, Tid.next(), key, writes, blocks.get)
      .fold(fail(_), identity)
    blocks ++= head.blocks

    val store = new Mst.Store(blocks.get)
    val nodes = MstOps.nodeCids(store, head.root).fold(fail(_), identity)
    val records = MstOps.entries(store, store.tree(head.root).toOption.get).fold(fail(_), identity)
    val exported = (head.commitCid +: nodes) ++ records.map(_._2)
    val car = Car.write(Vector(head.commitCid), exported.map(cid => cid -> blocks(cid)))

    val (roots, read) = Car.read(car).fold(fail(_), identity)
    assertEquals(roots, Vector(head.commitCid))
    assertEquals(read.length, exported.length)
    val imported = read.toMap
    val importedStore = new Mst.Store(imported.get)
    assertEquals(Repository.verify(
      Commit.decode(Cbor.decode(imported(head.commitCid)).toOption.get).toOption.get,
      key.publicKey, imported.get), Right(()))
    assertEquals(
      MstOps.entries(importedStore, importedStore.tree(head.root).toOption.get)
        .fold(fail(_), identity).map(_._1),
      records.map(_._1))
  }

  test("CAR archives reject tampered blocks, bad versions and truncation") {
    val cid = Cid.ofRaw(pds.crypto.Encoding.utf8("payload"))
    val valid = Car.write(Vector(cid), Vector(cid -> pds.crypto.Encoding.utf8("payload")))
    assert(Car.read(valid).isRight)
    assert(Car.read(valid.updated(valid.length - 1, (valid.last ^ 1).toByte)).isLeft)
    assert(Car.read(valid.dropRight(3)).isLeft)
    assert(Car.read(Array.emptyByteArray).isLeft)
    val badVersion = Cbor.encode(Node.Obj(Map(
      "roots" -> Node.Arr(Vector(Node.Link(cid))), "version" -> Node.Integer(2L))))
    assert(Car.read(Car.varint(badVersion.length.toLong) ++ badVersion).isLeft)
  }

  test("revisions order lexicographically with the commit chain") {
    val created = genesis
    val blocks = scala.collection.mutable.Map.from(created.blocks)
    val second = Repository.apply(did, created.commit, Tid.next(), key,
      Vector(Write.Create("app.bsky.feed.post", "3jqfcqzm3fo2j", record("hello"))), blocks.get)
      .fold(fail(_), identity)
    assert(second.commit.rev > created.commit.rev)
    assertEquals(second.previousRoot, Some(created.root))
  }
