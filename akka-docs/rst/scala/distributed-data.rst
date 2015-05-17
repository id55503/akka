
.. _distributed_data_scala:

##################
 Distributed Data
##################

*Akka Distributed Data* is useful when you need to share data between nodes in an
Akka Cluster. The data is accessed with an actor providing a key-value store like API.
The keys are strings and the values are *Conflict Free Replicated Data Types* (CRDTs).
All data entries are spread to all nodes, or nodes with a certain role, in the cluster
via direct replication and gossip based dissemination. You have fine grained control
of the consistency level for reads and writes.

The nature CRDTs makes it possible to perform updates from any node without coordination.
Concurrent updates from different nodes will automatically be resolved by the monotonic
merge function, which all data types must provide. The state changes always converge.
Several useful data types for counters, sets, maps and registers are provided and
you can also implement your own custom data types.

It is eventually consistent and geared toward providing high read and write availability
(partition tolerance), with low latency. Note that in an eventually consistent system a read may return an 
out-of-date value.

Using the Replicator
====================

The ``akka.cluster.ddata.Replicator`` actor provides the API for interacting with the data.
The ``Replicator`` actor must be started on each node in the cluster, or group of nodes tagged 
with a specific role. It communicates with other ``Replicator`` instances with the same path 
(without address) that are running on other nodes . For convenience it can be used with the
``akka.cluster.ddata.DistributedData`` extension.

FIXME introductionary code example

Update
------

To modify and replicate a data value you send a ``Replicator.Update`` message to the the local
``Replicator``.

The current data value for the ``key`` of the ``Update`` is passed as parameter to the ``modify``
function of the ``Update``. The function is supposed to return the new value of the data, which
will then be replicated according to the given consistency level.

The ``modify`` function is called by the ``Replicator`` actor and must therefore be a pure
function that only uses the data parameter and stable fields from enclosing scope. It must
for example not access ``sender()`` reference of an enclosing actor.

You supply a write consistency level which has the following meaning:

* ``WriteLocal`` the value will immediately only be written to the local replica,
  and later disseminated with gossip
* ``WriteTo(n)`` the value will immediately be written to at least ``n`` replicas,
  including the local replica
* ``WriteQuorum`` the value will immediately be written to a majority of replicas, i.e.
  at least **N/2 + 1** replicas, where N is the number of nodes in the cluster
  (or cluster role group)
* ``WriteAll`` the value will immediately be written to all nodes in the cluster
  (or all nodes in the cluster role group)

As reply of the ``Update`` a ``Replicator.UpdateSuccess`` is sent to the sender of the
``Update`` if the value was successfully replicated according to the supplied consistency
level within the supplied timeout. Otherwise a ``Replicator.UpdateFailure`` subclass is
sent back. Note that a ``Replicator.UpdateTimeout`` reply does not mean that the update completely failed
or was rolled back. It may still have been replicated to some nodes, and will eventually
be replicated to all nodes with the gossip protocol.

FIXME code example

You will always see your own writes. For example if you send two ``Update`` messages
changing the value of the same ``key``, the ``modify`` function of the second message will
see the change that was performed by the first ``Update`` message.

The ``Update`` message also supports a read consistency level with same meaning as described for
``Get`` below. If the given read consistency level is not ``ReadLocal`` it will first retrieve the
data from other nodes and then apply the ``modify`` function with the latest data.
If the read fails a ``Replicator.ReadFailure`` is replied to the sender of the ``Update``. In the
case of ``ReadFailure`` the update is aborted and no data has been changed.
To support "read your own writes" all incoming commands for this key will be
buffered until the read is completed and the function has been applied.

In the ``Update`` message you can pass an optional request context, which the ``Replicator``
does not care about, but is included in the reply messages. This is a convenient
way to pass contextual information (e.g. original sender) without having to use ``ask``
or local correlation data structures.

FIXME code example
 
Get
---

To retrieve the current value of a data you send ``Replicator.Get`` message to the
``Replicator``. You supply a consistency level which has the following meaning:

* ``ReadLocal`` the value will only be read from the local replica
* ``ReadFrom(n)`` the value will be read and merged from ``n`` replicas,
  including the local replica
* ``ReadQuorum`` the value will be read and merged from a majority of replicas, i.e.
  at least **N/2 + 1** replicas, where N is the number of nodes in the cluster
  (or cluster role group)
* ``ReadAll`` the value will be read and merged from all nodes in the cluster
  (or all nodes in the cluster role group)


As reply of the ``Get`` a ``Replicator.GetSuccess`` is sent to the sender of the
``Get`` if the value was successfully retrieved according to the supplied consistency
level within the supplied timeout. Otherwise a ``Replicator.GetFailure`` is sent.
If the key does not exist the reply will be ``Replicator.NotFound``.

FIXME code example

You will always read your own writes. For example if you send a ``Update`` message
followed by a ``Get`` of the same ``key`` the ``Get`` will retrieve the change that was
performed by the preceding ``Update`` message. However, the order of the reply messages are
not defined, i.e. in the previous example you may receive the ``GetSuccess`` before
the ``UpdateSuccess``.

In the ``Get`` message you can pass an optional request context in the same way as for the
``Update`` message, described above. For example the original sender can be passed and replied
to after receiving and transforming ``GetSuccess``.

You can retrieve all keys of a local replica by sending ``Replicator.GetKeys`` message to the
``Replicator``. The reply of ``GetKeys`` is a ``Replicator.GetKeysResult`` message.

Subscribe
---------

You may also register interest in change notifications by sending ``Replicator.Subscribe``
message to the ``Replicator``. It will send ``Replicator.Changed`` messages to the registered
subscriber when the data for the subscribed key is updated. Subscribers will be notified
periodically with the configured ``notify-subscribers-interval``, and it is also possible to
send an explicit ``Replicator.FlushChanges`` message to the ``Replicator`` to notify the subscribers
immediately.

The subscriber is automatically removed if the subscriber is terminated. A subscriber can
also be deregistered with the ``Replicator.Unsubscribe`` message.

FIXME code example

Delete
------

A data entry can be deleted by sending a ``Replicator.Delete`` message to the local
local ``Replicator``. As reply of the ``Delete`` a ``Replicator.DeleteSuccess`` is sent to
the sender of the ``Delete`` if the value was successfully deleted according to the supplied
consistency level within the supplied timeout. Otherwise a ``Replicator.ReplicationDeleteFailure``
is sent. Note that ``ReplicationDeleteFailure`` does not mean that the delete completely failed or
was rolled back. It may still have been replicated to some nodes, and may eventually be replicated
to all nodes.

A deleted key cannot be reused again, but it is still recommended to delete unused
data entries because that reduces the replication overhead when new nodes join the cluster.
Subsequent ``Delete``, ``Update`` and ``Get`` requests will be replied with ``Replicator.DataDeleted``.
Subscribers will receive ``Replicator.DataDeleted``.

FIXME code example

Data Types
==========

The data types must be convergent (stateful) CRDTs and implement the ``ReplicatedData`` trait,
i.e. they provide a monotonic merge function and the state changes always converge.

You can use your own custom ``ReplicatedData`` types, and several types are provided
by this package, such as:

* Counters: ``GCounter``, ``PNCounter``
* Sets: ``GSet``, ``ORSet``
* Maps: ``ORMap``, ``ORMultiMap``, ``LWWMap``, ``PNCounterMap``
* Registers: ``LWWRegister``, ``Flag``

Counters
--------

``GCounter`` is a "grow only counter". It only supports increments, no decrements.

It works in a similar way as a vector clock. It keeps track of one counter per node and the total 
value is the sum of these counters. The ``merge`` is implemented by taking the maximum count for
each node.

If you need both increments and decrements you can use the ``PNCounter`` (positive/negative counter).

It is tracking the increments (P) separate from the decrements (N). Both P and N are represented
as two internal ``GCounter``. Merge is handled by merging the internal P and N counters.
The value of the counter is the value of the P counter minus the value of the N counter.

FIXME code example

Several related counters can be managed in a map with the ``PNCounterMap`` data type.
When the counters are placed in a ``PNCounterMap`` as opposed to placing them as separate top level
values they are guaranteed to be replicated together as one unit, which is sometimes necessary for
related data.

Sets
----

If you only need to add elements to a set and not remove elements the ``GSet`` (grow-only set) is
the data type to use. The elements can be any type of values that can be serialized.
Merge is simply the union of the two sets.

FIXME code example

If you need add and remove operations you should use the ``ORSet`` (observed-remove set).
Elements can be added and removed any number of times. If an element is concurrently added and
removed, the add will win. You cannot remove an element that you have not seen.

The ``ORSet`` has a version vector that is incremented when an element is added to the set.
The version for the node that added the element is also tracked for each element in a so
called "birth dot". The version vector and the dots are used by the ``merge`` function to
track causality of the operations and resolve concurrent updates.

FIXME code example

Maps
----

``ORMap`` (observed-remove map) is a map with ``String`` keys and the values are ``ReplicatedData``
types themselves. It supports add, remove and delete any number of times for a map entry.

If an entry is concurrently added and removed, the add will win. You cannot remove an entry that
you have not seen. This is the same semantics as for the ``ORSet``.

If an entry is concurrently updated to different values the values will be merged, hence the
requirement that the values must be ``ReplicatedData`` types.

It is rather inconvenient to use the ``ORMap`` directly since it does not expose specific types
of the values. The ``ORMap`` is intended as a low level tool for building more specific maps,
such as the following specialized maps.

``ORMultiMap`` (observed-remove multi-map) is a multi-map implementation that wraps an
``ORMap`` with an ``ORSet`` for the map's value.

``PNCounterMap`` (positive negative counter map) is a map of named counters. It is a specialized 
``ORMap`` with ``PNCounter`` values.

``LWWMap`` (last writer wins map) is a specialized ``ORMap`` with ``LWWRegister`` (last writer wins register)
values. 

Note that ``LWWRegister`` relies on synchronized clocks and should only be used when the choice of
value is not important for concurrent updates occurring within the clock skew.

Instead of using timestamps based on ``System.currentTimeMillis()`` time it is possible to
use a timestamp value based on something else, for example an increasing version number
from a database record that is used for optimistic concurrency control.
 
When a data entry is changed the full state of that entry is replicated to other nodes, i.e.
when you update an map the whole map is replicated. Therefore, instead of using one ``ORMap``
with 1000 elements it is more efficient to split that up in 10 top level ``ORMap`` entries 
with 100 elements each. Top level entries are replicated individually, which has the 
trade-off that different entries may not be replicated at the same time and you may see
inconsistencies between related entries. Separate top level entries cannot be updated atomically
together.

Flags and Registers
-------------------

``Flag`` is a data type for a boolean value that is initialized to ``false`` and can be switched
to ``true``. Thereafter it cannot be changed. ``true`` wins over ``false`` in merge.

``LWWRegister`` (last writer wins register) can hold any (serializable) value.

Merge of a ``LWWRegister`` takes the the register with highest timestamp. Note that this
relies on synchronized clocks. `LWWRegister` should only be used when the choice of
value is not important for concurrent updates occurring within the clock skew.

Merge takes the register updated by the node with lowest address (``UniqueAddress`` is ordered)
if the timestamps are exactly the same.

Instead of using timestamps based on ``System.currentTimeMillis()`` time it is possible to
use a timestamp value based on something else, for example an increasing version number
from a database record that is used for optimistic concurrency control.

For first-write-wins semantics you can use the ``LWWRegister#reverseClock`` instead of the
``LWWRegister#defaultClock``.

Custom Data Type
----------------

You can rather easily implement your own data types. The only requirement is that it implements
the ``merge`` function of the ``ReplicatedData`` trait.

A nice property of stateful CRDTs is that they typically compose nicely, i.e. you can combine several
smaller data types to build richer data structures. For example, the ``PNCounter`` is composed of
two internal ``GCounter`` instances to keep track of increments and decrements separately.

FIXME code example of "simplified" PNCounter

FIXME perhaps show the shopping cart here?

Data types should be immutable, i.e. "modifying" methods should return a new instance.

The data types must be serializable with an :ref:`Akka Serializer <serialization-scala>`.
It is highly recommended that you implement  efficient serialization with Protobuf or similar
for your custom data types. The built in data types are marked with ``ReplicatedDataSerialization``
and serialized with ``akka.cluster.ddata.protobuf.ReplicatedDataSerializer``.

Serialization of the data types are used in remote messages and also for creating message
digests (SHA-1) to detect changes. Therefore it is important that the serialization is efficient
and produce the same bytes for the same content. For example sets and maps should be sorted
deterministically in the serialization.

FIXME code example of serializer

CRDT Garbage
------------

One thing that can be problematic with CRDTs is that some data types accumulate history (garbage).
For example a ``GCounter`` keeps track of one counter per node. If a ``GCounter`` has been updated
from one node it will associate the identifier of that node forever. That can become a problem
for long running systems with many cluster nodes being added and removed. To solve this problem
the ``Replicator`` performs pruning of data associated with nodes that have been removed from the
cluster. Data types that need pruning have to implement the ``RemovedNodePruning`` trait. 

Samples
=======

FIXME activator template with the samples

* Replicated Cache
* Replicated Metrics
* Replicated Service Registry
* VotingService
* ShoppingCart 

Limitations
===========

There are some limitations that you should be aware of.

CRDTs cannot be used for all types of problems, and eventual consistency does not fit
all domains. Sometimes you need strong consistency.

It is not intended for *Big Data*. The number of top level entries should not exceed 100000.
When a new node is added to the cluster all these entries are transferred (gossiped) to the
new node. The entries are split up in chunks and all existing nodes collaborate in the gossip,
but it will take a while (tens of seconds) to transfer all entries and this means that you 
cannot have too many top level entries. The current recommended limit is 100000. We will
be able to improve this if needed, but the design is still not intended for billions of entries.

All data is held in memory, which is another reason why it is not intended for *Big Data*.

When a data entry is changed the full state of that entry is replicated to other nodes. For example,
if you add one element to a Set with 100 existing elements, all 101 elements are transferred to
other nodes. This means that you cannot have too large data entries, because then the remote message
size will be too large. We might be able to make this more efficient by implementing
`Efficient State-based CRDTs by Delta-Mutation <http://gsd.di.uminho.pt/members/cbm/ps/delta-crdt-draft16may2014.pdf>`_.

The data is only kept in memory. It is redundant since it is replicated to other nodes 
in the cluster, but if you stop all nodes the data is lost, unless you have saved it 
elsewhere. Making the data durable is a possible future feature, but even if we implement that
it is not intended to be a full featured database.

Learn More about CRDTs
======================

* `The Final Causal Frontier <http://www.ustream.tv/recorded/61448875>`_
  talk by Sean Cribbs
* `Eventually Consistent Data Structures <https://vimeo.com/43903960>`_
  talk by Sean Cribbs
* `Strong Eventual Consistency and Conflict-free Replicated Data Types <http://research.microsoft.com/apps/video/default.aspx?id=153540&r=1>`_
  talk by Mark Shapiro
* `A comprehensive study of Convergent and Commutative Replicated Data Types <http://hal.upmc.fr/file/index/docid/555588/filename/techreport.pdf>`_
  paper by Mark Shapiro et. al.

Dependencies
------------

To use Distributed Data you must add the following dependency in your project.

sbt::

    "com.typesafe.akka" %% "akka-distributed-data" % "@version@" @crossString@

maven::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-distributed-data_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

Configuration
=============
  
// FIXME include config, or add and link to the common configuration page
 