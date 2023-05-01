# Resource Registry

One effective way of sending messages to the right destination would be to send
them from one actor to the other through the actor hierarchy tree. But that
would be inefficient if the tree hierarchy is deep, and would create a
bottleneck for the root actor, which would need to parse all the messages.

The resource Registry keeps a data structure of the path to containers so that
given `Uri.Path` it can return a ActorRef for the closest container, the
remaining path and the closest default container, for access control reasons.

## What is the resource registry used for?

1. When the web server receives a message it uses the resource registry to send
   it along to the closest container of the right actor it knows of
2. CmdMessage and ScriptMsg being FreeMonads can jump from one resource to
   another and so they also need the registry. These will be used by the Guard
   to fetch data on the web or cache for example.

In order to enable those features we need:

3. Containers to update the registry when they create or delete
    1. a new container
    2. their own ACR - they need to update the attribute

With point (1) above the server knows if a request is going to a particular
subtree. But that is not the case for (2) where a request may be going to the
Wild Web. So that means the Registry needs to be able to map more than just
paths, but also URLs, as we will want to have actors responsible for different
remote servers, fetching the data and caching it.

So that means the actor registry should have a method like

```scala
def actorFor(uri: Uri): Either[ActorRef[X], (Path, ActorRef[Y], ACInfo)]
```

because the Path and ACInfo don't make sense for non-local info. ACInfo is
essentially meant to help the local server track the closest default ACR... But
when interacting with the remote server that info is just given to us by the
server in the response header.

## Should the Resource Registry be an Extension?

#### Some reasons in Favor:

1. We want one Resource Registry per ActorSystem
2. We want to avoid having accidentally more than one Registry
3. Currently, we have both containers and messages with registry pointers, which
   is a bit of a mess.

* Q1. How expensive would it be to fetch the singleton from the system only when
  needed?
* Q2: Would only having it as a singleton clean up the code?

#### Some reasons against:

We don't actually use the Extension framework. That is because we have been
passing the registry around as `given`s in

+ the BasicContainers
+ CmdMessage and ScriptMsg (so in all http req messages!)

So we have to ask:

* Q3: would it in fact be possible to avoid passing the register around for the
  Cmd and Script messages in particular?

It's a question of giving access to the local context. But that should be easy
to setup.

### further considerations

1. I was hoping the register would be created with a root container set.
    1. But it turns out that is problematic because it means we need to know
       the `actorRef` of the root container before we create the registry. But
       if we have to pass the registry along in the creation of a container this
       can't be done
    2. If we accept that the registry should also know about cache and web
       actors and that we may want only parts of the document space to be served
       by Solid then we cannot be initially set up with the certainty that the
       root container is there.

#### Functional Analysis

So really the registry needs to be more general and we can't assume it will
return a ActorRef. So we really need something like:

```scala
def actorFor(uri: Uri): Try[Either[(ActorRef[Y], Path, ACInfo), ActorRef[X]]]
```

That is `1 + AR + Path⨉AR⨉ACInfo = 1 + AR⨉(1 + Path⨉ACInfo)`
If we always have an actor around that can deal with errors then we can remove
the first 1. So we could have these two equivalent functions

```scala
def actorForX(uri: Uri): (ActorRef[X], Option[(Path, ACInfo)])
def actorForV(uri: Uri): Either[(ActorRef[Y], Path, ACInfo), ActorRef[X]]
```

The disjunction allows us to have different `ActorRef[_]` types, which could be
an advantage. Another advantage is that it allws us to decompose our function
more clearly.

First we can spit a url into either being local or remote

```scala
def split: Url => LocalUrl + RemoteUrl 
```

Then we can have two ActorFor functions

```scala
def actorForL(url: LocalUrl): (ActorRef[X], Path, ACInfo)
def actorForR(url: RemoteUrl): ActorRef[X]
```

such that

```scala
def actorFor(url: Uri) = split(url).fold(actorForR, actorForL)
```

But we could also split LocalUrl into

```scala
def components(url: LocalUrl): (UrlLoc, Path)
```

and then use a function from the result to the actor and info

```scala
def actorForSplit(pre: UrlLoc, path: Path): (ActorRef[X], Path, ACInfo)
```

so that the original can be reconstituted

```scala
def actorForL(url: LocalUrl) = components(url) andThen actorForSplit
```

but the important point is that we can curry `actorForSPlit`

```scala
def actorForSplit(pre: UrlLoc)(path: Path): (ActorRef[X], Path, ACInfo)
```

so that we can give the following function

```scala
(path: Path) => (ActorRef[X], Path, ACInfo)
```

to an actor dealing with a specific part of the tree hierarchy, which it will
use for requests on the given path sent to it.

And that is just what we currently have in our Web server whose root is an LDPC
and described in point (1) above. It can take requests from the web with a path
and use that function to get the information about where to send the messages on
to.

But as we saw we really need the more general

```scala
def actorForV(uri: Uri): Either[(ActorRef[Y], Path, ACInfo), ActorRef[X]]
```

#### OO Analysis

A disjunction is modelled in OO programming in terms of exclusive subtypes. So
that our `actorForV` could return an implementation of a trait that always has
ActorRef, or that has more information...

But really in OO we would like to hide the data and present just what can be
done with it using the underlying data. So perhaps we would rather like to have
our

```scala
trait SolidPostOffice

:
def send(cmd: Cmd): Unit
```

This could allow the localisation of all the code for sending messages that are
not being shipped to their destination.

But it would also need the methods to update the actor refs and properties of
containers. If we have more than one root LDPC then this means we will need
a `Map[Uri,Resgistry]` that can find which registry to update given a url.
(That was easier when we assumed only one registry)

So we could go for

```scala
trait SolidPostOffice(system: ActorSystem[_])

:
// we need to pass the ActorSystems
def send(cmd: Cmd)(using ac: ActorContext[X]
): Unit
//change state
def addRoot(uri: Uri, actorRef: R): Unit
def addActorRef(uri: Uri, actorRef: R, optAttr: A): Unit
def removePath(path: Uri): Unit
```

It is the `SolidPostOffice` that should be an Extension. Note that a
SolidPostOffice will be needed even if we don't need an Extension for the local
containers. The reason is that we need a way to know which actors to send
messages to for remote servers.

##### Problem with OO

The send function needs to do completely different thing when the root actor
calls it and when it is being called by a script.
1. the root actor makes an ask query
2. the Messages.send is just forwarding the CmdMessage to the actor returned in
   the Registry (but really it should be sending it on to any actor). That is
   because CmdMessage contains the return actor to which answers will be sent
3. Guard? It also uses ask. It does not use the registry, even though it 
   could, because it asks the actor in which it is to execute the ScriptMsg, 
   which is then sent to the previous point (2). But it could also be sent 
   directly to the right ACL actor.
 
Actually in cases  (1) and (2) a CmdMessage is wrapped in a Route or a Do.

So how do we distinguish the two cases of ask and forwarding?

#### TODO immediately

1. For the registry to work, the creator of the container has to register the
   created container, not the created container. That would remove the current
   logical deadlock. Then the Container if it needs the registry can call the
   registry as an extension.
2. Find out if we can use the extension mechanism for the Container uses
   (that is needed for 1 above, and should be easy to do)
2. Find out if we can actually do everything by just calling an extension.
    1. most important the Command types.

## Improvement ideas for Registry

1. Currently, the registry is an immutable data structure with one root node is
   wrapped in an atomic reference. Any change in the whole tree can update that
   root node, which for very large trees could be problematic. Instead one could
   have a tree where each node is an atomic reference, meaning that only the
   smallest subtree would need to be updated, leading to a lot less contention
2. It makes sense that we could end up running an ldp container only on portions
   of a web server
3. The registry should also be able to pass on Cmds to remote servers or the
   cache.
4. There may be a maximum depth at which it is less interesting to keep a
   registry. Perhaps 4 or 5 deep it becomes faster just to have the messages
   passed from container to container, since those already act as a Directory of
   entries, i.e. a DB. 
