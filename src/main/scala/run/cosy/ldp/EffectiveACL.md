# Access Control Link

 * [Effective ACL and the 2n+1 problem](#effective-acl-and-the-2n1-problem)
 * [Implementations](#implementation)
 * [How to Deal with Updates](#how-to-deal-with-updates)

Note: Access Control is not part of LDP but of Solid. This suggests that those should be separate components.

## Effective ACL and the 2n+1 problem

The Web Access Control logic is defined in the [Web Access Control](https://solidproject.org/TR/wac) spec. The section [ACL Resource Discovery](https://solidproject.org/TR/wac#effective-acl-resource), explains how to determine the effective acl resource by starting from the acl of a resource. If the acl does not exist, then one should search for the resource container's acl, and if that does not exist, one should search for the container's containers acl, etc... The problem is that for a client [Effective ACL Resource discovery requires 2n+1 requests](https://github.com/solid/web-access-control-spec/issues/99). Given the limitations on the speed of light, that type of requirement is much too costly for a client trying to find the effective acl. Furthermore, for any Pod that has more than one owner, this link following will have to be done just to find out who the controller of the resource is if one wants to create it. 

To solve the [2n+1 problem](https://github.com/solid/web-access-control-spec/issues/99) I will use [this simple proposal](https://github.com/solid/specification/issues/325#issuecomment-1474817231) to link to the effective acl

```http
Link: </defaul.ac>; rel="https://www.w3.org/ns/auth/acl#accessControl`
```

## Implementation

The main implementation problem is to efficiently to allow the server to find the effective url for a resource. 

This simple answer here is to follow the [ACL Resource Discovery](https://solidproject.org/TR/wac#effective-acl-resource) algorithm. This could be done by sending a message up the actor hierarchy until an existing default acl is found. (we are imagining a tree going up) This is a lot more efficient when done on the server, especially when messages are sent to the same machine, as there is no serialisation, tcp connection, deserialisation cost to be paid.

Furthermore, on the server, things can be more efficient: it is easy for the container to keep information stating whether its acl exists and has default rules. If it does, the number of messages to be sent for a distance n between the default rule and the effective rule can just be n. This could also be done on the client, but it would require to have a Link relation specifying that, in which case we might as well have an "effective wac" link.

So caching of where the effective rule is, is going to be vital. So how should we do it? Should we do it when 
1. building the hierarchy to get to the resource? 
2. or, should the request go up the hierarchy until a default rule container is found?

The problem with 1. going up the hierarchy - is that this could slow down dramatically the serving up of resources. Imagine that we have a resource 10 levels deep and that each container has an acl perhaps with a default rule. Then the request message going to the final resource will need to parse the acl of each of the 9 intermediate resources first, before it arrives at the resource, if it even exists. 

Solution 2. going down the hierarchy seems more appropriate then. It has the advantage that the parsing of intermediate acls could be done in parallel as the request is sent up to its destination. That resource can then check if it has a direct ACR. If not, it sends a message down the hierarchy to find the first container with a default. 

This result could be cached by the containers, which means though that any edit of ACRs of containers has to notify resources upstream of the changes - up to the next container with a default ACR that is. To make the system more sturdy containers with defaults could send a message upstream a few times after an edit. 

So let us say a container keeps info about the
`defaultAclActor: Either[Bool,Path]` which points to a `Right(default_acl)` if it is known or `Left(false)` otherwise. A request going up a new Container hierarchy would set this value to `Left(false)` and set off an actor to fetch the acl for the container. If no acl file existed, then it would be `Left(true)`. `Left(true)` is problematic because it means that there is no default, and so potentially every resource needs to have its own acl. 

Similarly, a resource actor can have 
`defaultAclActor` set. One first creation the actor sets it to `Left(false)` for it does not know what the default is. It then would check to see if the direct acl exists. If it does, then `defaultAclActor` would point to that acl with `Right(directAcl)`. Otherwise it would need to ask the container. The container would respond with what it knows. If it does not know, it would search up the hierarchy.

Because a request always goes through the container, the Container should send the request message to the resource actor with the `defaultAcl` info it knows of on creation, or even on request, so that the actor gets the latest default. That would reduce the need for the resource actor to ask the container for probably the most important information it needs to grant or deny access.

## How to deal with updates?


Assume an actor hierarchy of `/a/b/c/d/e/f.ttl` 
Assume that the acl of `/a/b/` is the default for all
resources contained therein.

Now if the ac of `/a/b/` is removed then messages would have to be sent to all the children of `/a/b/` to let it be know that the default is now `/a/`. If then someone else creates an acl in `/a/b/c` then there is a risk that messages to change `/a/b/c/d/` will arrive out of order, perhaps one telling `d` should be the default and another `a`. This is going to be a nightmare
to maintain.


The most "secure" way to make sure every request knows
what the default container's acl is, would be to pass the messages through the actor hierarchy. Then every message would just pick up the last ACL as it goes through the hierarchy, and so the message would know
what the right default is when it arrives, ignoring that
is changes that may occur asynchronously, but the next request will fix that. 
The only problem for that is that it would be very slow and create message bottlenecks at the root actor. Indeed, that is why we created the `ResourceRegistry`.

With the `ResourceRegistry` (which should actually be called the `ContainerRegistry`) we can have message sent directly to the closest container eg. to `/a/b/c/d/` which means that it won't know that either `a` or `d` should be the default. So then it needs to completely rely on what the container knows. But we saw the container can get messages out of order.

What if instead we have the containers update the `ResourceRegistry` on whether they have an ACL? 
That would allow messages to be sent to the closest actors with the right defaults, and yet also allow messages to walk their way up through the tree. And that is what we want, since we may have
only built up `/a/b/` and the request may be for `/a/b/c/d`, so that the `ResourceRegistry` can only get us to `b` and the rest will need to be walked through.

