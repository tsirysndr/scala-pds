package pds.protocol

import pds.protocol.Mst.{Child, Entry, Store, Tree}

/** Insertion, removal and traversal for the Merkle search tree. Every result
  * is a fresh tree; subtrees that did not change stay referenced by CID.
  */
object MstOps:
  def get(store: Store, tree: Tree, key: String): Either[String, Option[Cid]] =
    val index = gtOrEqualIndex(tree, key)
    tree.entries.lift(index) match
      case Some(Entry.Leaf(found, value)) if found == key => Right(Some(value))
      case _ =>
        tree.entries.lift(index - 1) match
          case Some(Entry.Sub(child)) => store.resolve(child).flatMap(get(store, _, key))
          case _                      => Right(None)

  def put(store: Store, tree: Tree, key: String, value: Cid): Either[String, Tree] =
    if !Mst.isValidKey(key) then Left("Invalid repository key")
    else add(store, tree, key, value, Mst.keyHeight(key))

  private def add(store: Store, tree: Tree, key: String, value: Cid, height: Int): Either[String, Tree] =
    for
      layer <- store.layerOf(tree)
      result <-
        if height == layer then addHere(store, tree, layer, key, value)
        else if height < layer then addBelow(store, tree, layer, key, value, height)
        else addAbove(store, tree, layer, key, value, height)
    yield result

  private def addHere(store: Store, tree: Tree, layer: Int, key: String, value: Cid): Either[String, Tree] =
    val index = gtOrEqualIndex(tree, key)
    tree.entries.lift(index) match
      case Some(Entry.Leaf(found, _)) if found == key =>
        Right(Tree(layer, tree.entries.updated(index, Entry.Leaf(key, value))))
      case _ =>
        tree.entries.lift(index - 1) match
          case Some(Entry.Sub(child)) =>
            for
              sub <- store.resolve(child)
              split <- splitAround(store, sub, key)
              (left, right) = split
              replacement = left.map(fresh).toVector ++
                Vector(Entry.Leaf(key, value)) ++ right.map(fresh).toVector
            yield Tree(layer, tree.entries.patch(index - 1, replacement, 1))
          case _ =>
            Right(Tree(layer, insertAt(tree.entries, index, Entry.Leaf(key, value))))

  private def addBelow(
      store: Store, tree: Tree, layer: Int, key: String, value: Cid, height: Int
  ): Either[String, Tree] =
    val index = gtOrEqualIndex(tree, key)
    tree.entries.lift(index - 1) match
      case Some(Entry.Sub(child)) =>
        for
          sub <- store.resolve(child)
          updated <- add(store, sub, key, value, height)
        yield Tree(layer, tree.entries.updated(index - 1, fresh(updated)))
      case _ =>
        for updated <- add(store, Tree(layer - 1, Vector.empty), key, value, height)
        yield Tree(layer, insertAt(tree.entries, index, fresh(updated)))

  private def addAbove(
      store: Store, tree: Tree, layer: Int, key: String, value: Cid, height: Int
  ): Either[String, Tree] =
    for
      split <- splitAround(store, tree, key)
      (left, right) = split
      raised = (1 until (height - layer)).foldLeft((left, right)) { case ((l, r), _) =>
        (l.map(parent), r.map(parent))
      }
      entries = raised._1.map(fresh).toVector ++
        Vector(Entry.Leaf(key, value)) ++ raised._2.map(fresh).toVector
    yield Tree(height, entries)

  private def parent(tree: Tree): Tree = Tree(tree.layer + 1, Vector(fresh(tree)))

  private def splitAround(store: Store, tree: Tree, key: String): Either[String, (Option[Tree], Option[Tree])] =
    val index = gtOrEqualIndex(tree, key)
    val leftEntries = tree.entries.take(index)
    val rightEntries = tree.entries.drop(index)
    leftEntries.lastOption match
      case Some(Entry.Sub(child)) =>
        for
          sub <- store.resolve(child)
          split <- splitAround(store, sub, key)
          (subLeft, subRight) = split
          left = leftEntries.dropRight(1) ++ subLeft.map(fresh).toVector
          right = subRight.map(fresh).toVector ++ rightEntries
        yield (option(tree.layer, left), option(tree.layer, right))
      case _ =>
        Right((option(tree.layer, leftEntries), option(tree.layer, rightEntries)))

  private def option(layer: Int, entries: Vector[Entry]): Option[Tree] =
    if entries.isEmpty then None else Some(Tree(layer, entries))

  def delete(store: Store, tree: Tree, key: String): Either[String, Tree] =
    deleteRecurse(store, tree, key).flatMap(trimTop(store, _))

  private def deleteRecurse(store: Store, tree: Tree, key: String): Either[String, Tree] =
    val index = gtOrEqualIndex(tree, key)
    tree.entries.lift(index) match
      case Some(Entry.Leaf(found, _)) if found == key =>
        (tree.entries.lift(index - 1), tree.entries.lift(index + 1)) match
          case (Some(Entry.Sub(before)), Some(Entry.Sub(after))) =>
            for
              left <- store.resolve(before)
              right <- store.resolve(after)
              merged <- appendMerge(store, left, right)
            yield Tree(tree.layer, tree.entries.patch(index - 1, Vector(fresh(merged)), 3))
          case _ =>
            Right(Tree(tree.layer, tree.entries.patch(index, Vector.empty, 1)))
      case _ =>
        tree.entries.lift(index - 1) match
          case Some(Entry.Sub(child)) =>
            for
              sub <- store.resolve(child)
              updated <- deleteRecurse(store, sub, key)
            yield
              if updated.entries.isEmpty then
                Tree(tree.layer, tree.entries.patch(index - 1, Vector.empty, 1))
              else Tree(tree.layer, tree.entries.updated(index - 1, fresh(updated)))
          case _ => Left("Record was not found in the repository")

  private def appendMerge(store: Store, left: Tree, right: Tree): Either[String, Tree] =
    (left.entries.lastOption, right.entries.headOption) match
      case (Some(Entry.Sub(before)), Some(Entry.Sub(after))) =>
        for
          last <- store.resolve(before)
          first <- store.resolve(after)
          merged <- appendMerge(store, last, first)
        yield Tree(left.layer, left.entries.dropRight(1) ++ Vector(fresh(merged)) ++ right.entries.drop(1))
      case _ => Right(Tree(left.layer, left.entries ++ right.entries))

  private def trimTop(store: Store, tree: Tree): Either[String, Tree] =
    tree.entries match
      case Vector(Entry.Sub(child)) => store.resolve(child).flatMap(trimTop(store, _))
      case _                        => Right(tree)

  /** All leaves in key order. */
  def entries(store: Store, tree: Tree): Either[String, Vector[(String, Cid)]] =
    tree.entries.foldLeft[Either[String, Vector[(String, Cid)]]](Right(Vector.empty)) {
      case (acc, Entry.Leaf(key, value)) => acc.map(_ :+ (key -> value))
      case (acc, Entry.Sub(child)) =>
        for
          list <- acc
          sub <- store.resolve(child)
          nested <- entries(store, sub)
        yield list ++ nested
    }

  /** Every node CID in the tree, for CAR export and garbage collection. */
  def nodeCids(store: Store, cid: Cid): Either[String, Vector[Cid]] =
    for
      tree <- store.tree(cid)
      children <- tree.entries.collect { case Entry.Sub(Child.Stored(child)) => child }
        .foldLeft[Either[String, Vector[Cid]]](Right(Vector.empty)) { (acc, child) =>
          for
            list <- acc
            nested <- nodeCids(store, child)
          yield list ++ nested
        }
    yield cid +: children

  private def fresh(tree: Tree): Entry = Entry.Sub(Child.Fresh(tree))

  private def insertAt(entries: Vector[Entry], index: Int, entry: Entry): Vector[Entry] =
    entries.take(index) ++ Vector(entry) ++ entries.drop(index)

  private def gtOrEqualIndex(tree: Tree, key: String): Int =
    val found = tree.entries.indexWhere {
      case Entry.Leaf(candidate, _) => candidate >= key
      case _                        => false
    }
    if found >= 0 then found else tree.entries.length
