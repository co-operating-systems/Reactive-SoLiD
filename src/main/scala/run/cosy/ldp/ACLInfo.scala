package run.cosy.ldp

import akka.actor.typed.ActorRef
import run.cosy.ldp.ACLInfo.ACLActorInfo
import run.cosy.ldp.Messages.{Do, Route, ScriptMsg}

object ACLInfo:

   /** see [../README](../README.md). Note: we started with enums but I found it really difficult
     * then to cast disjunctions of those down to what we here have as MsgACL, mostly because
     * singletons like NotKnown are then of type DefaultACL
     */
   sealed trait ACLActorInfo

   // we make having one's own ACL a special case as this can be directly verified on creation
   object OwnACL extends ACLActorInfo

   // MsgACL subtypes are those that are passed in messages
   sealed trait MsgACL extends ACLActorInfo

   // there should always be an ACL for the root container so this indicates a server error
   object NotKnown extends MsgACL

   // the actorRef path should be useable for constructing a relative URL
   case class ParentAcl(actor: ActorRef[Route], fromRegistry: Boolean) extends MsgACL
   
   extension (aclInfo: ACLActorInfo)
     def resolve(current: => ActorRef[Route]): ACLActorInfo =
       aclInfo match
         case OwnACL =>  ParentAcl(current, false)
         case x => x

end ACLInfo
