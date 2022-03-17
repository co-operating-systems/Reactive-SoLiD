## Authorization Reasoning

todo: clarify namespaces listed

### Fully Trusted Graphs 

A Guard that has to reason about whether an Agent A - identified by a set
of properties P - is authorized to access a resource R has to go by the 
rules T associated with R via the headers given in a 401 response as for
example described by the [WAC spec](https://solidproject.org/TR/wac). 

The Guard will follow those rules, thereby making them describe the Guard's 
behavior leading as a consequence to the world being as described.

With WAC documents containing `imports` relations (see [issue 210](https://github.com/solid/authorization-panel/issues/210)) a Guard can even build a DataSet
the union of which will be considered to provide the rules for the Guard.

The Guard also has verified attributes from the Authentication Process, proving
one of a number of attributes of an agent. Such as the following graphs G1-5

```N3
   G1 = { A owl:sameAs <https://bblfish.net/people/henry/card#me> . } 
   G2 = { A foaf:mbox <mailto:henry.story@bblfish.net> . }            
   G3 = { A cert:key <https://acme.com/#> . }                         
   G4 = { A cert:key [ cert:modulus "..."; ... ] . }  
   G5 = { A cert:key <did:key:z6MkiTBz1ymuepAQ4HEHYSF1H8quG5GLVVQR3djdX3mDooWp> }.                 
```

The question the Guard is then required to ask is if the Agent under the given 
description is allowed access to the given resource for the mode requested. 
Or: having filtered the rules down to those that apply to the resource R in 
the required mode, the Guard has to find out if those rules allow agent A access.

### Partially Trusted Graphs

Those Rules may refer to data about a user that is remote. 
For example the `agentGroup` class may refer to a remote group G1 
We expect the information in the remote resource of interest to only be
data about the members of the group, not other access control rules.
We delegate membership of a group to another agency, but we do not
delegate rule setting to them. So if we give read-access to members
of a group, the author of the group resource can add or remove agents from
the group, but not give them write or control access too.

It would therefore be wrong to merge the remote group G1
with the rules L to form a new graph Lg in order to determine 
what rules are appropriate. On the other hand Lg would be fine
to evaluate information about the agent A. 
That would allow one to add extra information 
to a WAC graph about an agent that could be used
in reasoning about agents. 

So we really have to distinguish carefully
 1. between the rules Graph the Guard fully endorses
 2. extra external information controlled by other agents

Note there may be more than one resource (2). For example an 
access control rule may have more than one group published by 
different agents. It would not be appropriate to take the unions 
of all those graphs to determine group membership, as that would 
allow a group in one company to assign membership in a group
in another company. For an ASK query that looses the information 
as  to where the group membership was declared this would not
be so problematic. But it seems easy to imagine slightly more 
complex cases where it would make a difference.

### Types of Partially Trusted Data

What type of partially trusted data can we have? 
 1. foaf:Groups 
 2. User Identity information

We looked at (1) above. We can illustrate (2) by noting that at its simplest
we can identify an Agent by
  * WebID
  * KeyID
  * e-mail (eg. OpenID)
  * other attributes ...

The Trusted Rules can give access to an  agent in a number of ways. 
An example will make this clear:

```Turtle
<#auth1> a wac:Authorization;
   wac:accessTo <r1>;
   wac:mode wac:Read;
   wac:agent 
        [ cert:key <did:key:z6MkiTBz1ymuepAQ4HEHYSF1H8quG5GLVVQR3djdX3mDooWp> ],
        [ cert:key [ cert:modulus "..."^^xsd:hexBinary, 
                     cert:exponent 1024]],           //L0 
        [ cert:key <https://company.com/keys/#k1> ], //L1
        [ foaf:mbox <mailto:john@acme.com> ],        //L2
        <https://bblfish.net/people/henry/card#me>   //L3
```

* L0: the key is given as a did key or a literal, and there is no external document
to refer to. In that case the user can only be identified by prooving control of 
that key. It would not help to have someone identified by a WebID where that WebID Profile contains
the exact same key, since 
  * anyone could add someone else's key to their WebID Profile,
  * yet the proof of the WebID could have been given by e-mail verification.
* L1 is also identifying the agent via a keyId, specified by reference.
If the literal values match then the identification works, because the authentication 
has proven the Agent knows the private key. 
But it may be useful if identity of the agent contains the private key used. 
* L2: identifies the agent via a e-mail inbox, which could be verified using OpenID.
  Having a WebID of the john@acme.com and his profile linking to that mailbox would
  not constitute a proof of L3, since a WebId Profile Document can link to any mbox. 
  It would only work if there were a link to a verifiable mbox claim.
* L3: identifies an agent via their WebID. The WebID can be determined by 
finding a Link from the WebID to a key using HTTPSig or Solid-OIDC 

### What can be trusted?

Let's imagine we have a rule with 100s of agent identified 
by WebID. Let us also say that the agent A authenticated with did:key.
The Guard will now have to search each of the WebIDs Profiles to find
the one that makes an assertion from the WebID to the did key.

```Turtle
<#i> cert:key <did:key:z6MkiTBz1ymuepAQ4HEHYSF1H8quG5GLVVQR3djdX3mDooWp> .
```

If such a statement is found then A should be given access, because
the rules have delegated responsibility on who `<#i>` to the profile document 
and that is making `<#i>` responsible for  the behavior of A. 

This is different from the situation discussed in the first bullet
of the previous section where the agent is authenticated by a 
WebID (via the inverse functional property to 
an mbox perhaps) but the rule only allows a `did:key` which happens to
be present in the WebID profile. This is because the rules do not give
responsibility to a profile document and neither do they give access 
via an mbox. The claim that the proven WebID is related to the key is made
by the agent authenticating, but that agent has not proven control of the
key.

Is it that we can use information linked-to from the rules documents 
but not information linked to from the verified claims sent by A? That
may well be: the authenticated statements are made by the Agent authenticating
and each one of those need to be proven as they must be suspect. 
The documents linked to from the rules are giving responsibility away.

### Complexity of reasoning for the Guard

Let us come back to our example above, where we imagine we 
have a rule with 100s of agent identified by WebID. We saw
that if a user had authenticated with a `did:key` or other
representions of a key, then the Guard would need to search through
each of the WebID Profiles listed for a link to that key. That could
be very slow. Caching the documents could help, but not if the key
is not found, as the reason for that could be that one of the
profiles updated their key, requiring at most to fetch all the resources
again!

It is in the interest then of a client wanting quick authentication
to specify the WebID document in which the statement is made, in addition
to proving access to the key. The Guard could use that extra piece of
information to immediately look up the right document. 

The Authentication process could verify the information is indeed there, 
but that may be wasted work if the Guard finds that the given WebID is not 
even allowed access. So it looks like in our Authentication step we end with two 
types of statements: Verified ones and (unverified) hints for the Guard.

Indeed having the client pass hints to the server makes a lot of sense, since
the client having read the rules knows exactly why it is presenting the 
information it is. As cases get more complicated these hints will get more
important. Take for example a rule that allows access to S'friends of a friend.
Proving a WebID in that case is not helpful in the indirect case, 
as that leaves the responsibility on the server to find the intermediate friend.
```sparql
  ?s :knows ?p .
  ?p :knows ?webid .
```
If a person has 150 friends then that could lead to the guard having to make
150 requests. But given the intermediary hint, the answer could be solved in
one go. 

Clearly as the search space gets bigger the savings can be greater.
For friend of a friend of a friend relations, one could 
reduce the search space by potentially `150*150 == 22500` down to 1, 
assuming  every person has 150 friends.

There could easily be more than one answer to the problem, as there may
be many intermediary friends that make one the foaf of some one. That
is an extra complicating factor that needs to be looked into when 
extending what we are looking at now. Furthermore the client may be
using some reasoning to infer some triples, and want to help the 
Guard to apply those rules.

### Initial steps to reduce complexity

In order to test the space of possibilities we want to start with 
simple cases. For our present purpose we limit ourselves to agent 
groups described by keyIds, literal keys, WebIDs, and small foaf network 
and we limit authentication to HttpSig which verifies a keyId equivalent. 

Given httpSig authentication the missing info for WebID groups
is the associated WebID and for a foaf network the intermediate 
friend. It is clear that both of these cases allow for an ad-hoc answer: 
one can add an http header to pass these URLs. General answers would 
require one to have a language that can express generic path
reasoning across documents. (todo: search for work in this 
direction)

So since this is so simple let us detail the cases out clearly.

#### Rule based on `wac:agent` link to WebID

The client knows that it can authenticate as `bob:i` using 
[HttpSig](https://github.com/bblfish/authentication-panel/blob/main/proposals/HttpSignature.md)
because the rules contains the triple 

```Turtle
<#R> wac:agent bob:i .
```
and because the 401 returns a relevant `WWW-Authenticate: HttpSig` header.
What is needed is, assuming we are using a `did:key` to add the hint linking the
key and the webId. 

```sparql
bob:i cert:key ?key .
```

Note that to tie in with the work on Json Crytpography we
would like to also move to `security:controller` in which case the
hint should be of the form

```sparql
?key security:controller bob:i .
```

Once the key is added, we have a triple statement that has not 
been verified. How should it be used by the Guard? Well it is telling
the Guard the relation to be followed from the WebID to the verified key.
But where is that relation to be found? It could be found either in the
WAC document or in the WebID Profile. The choice here is small enough that
one could leave it to the Guard to try out these two possibilities. Let us 
see if the more complicated cases require something more.

#### Rule based on wac:agentGroup link 

A more complex case would be a rule with multiple remote `wac:agentGroup` 
links that identify their members with WebIDs whose profile contains 
a linked-to WebKey. 

The WAC Document could contain many `agentGroup`s with the relevant one
in this case being

```Turtle
<#R> wac:agentGroup <https://mit.edu/philosophy/groups/g1#> .
```

The MIT philosophy group could contain this triple among many

```Turtle
<#> foaf:member bob:i .
```

And bob's profile document could contain the triple

```Turtle
<#i> cert:key </keys/k1#> .
```

requiring a further dereferencing of `</keys/k1>`.  We have two important
links occurring in two graphs

```Trig
<https://mit.edu/philosophy/groups/g1> {
  <https://mit.edu/philosophy/groups/g1#> foaf:member bob:i .
}
<https://bob.name/> {
  <https://bob.name/#i> cert:key <https://bob.name/keys/k1#> .
}
```

Do we need a link from the rule to the Group or the WebID? That would make
the reasoning hint more precise, but would also use up more space. And it
would work better if rules had names.

### Encoding of reasoning hints.

  It looks like we need to express the paths taken from a rule to the
key, where the path constitutes a proof. (Q: Is that all that is needed?)
We may want to specify when a jump from one document to another needs to be
made. 

#### With RFC 5988 Web Linking?

Could we use [RFC 5988 Web Linking](https://datatracker.ietf.org/doc/html/rfc8288)?
We could add the path as `Link` headers ordered by their position in
the HTTP header (as permitted by [§2 Links](https://datatracker.ietf.org/doc/html/rfc8288#section-2): 
"applications that wish to consider ordering significant can do so"). 
In addition to what RFC 5988 offers, we need to 
 * encode that these links are meant as hints to the WAC reasoner, with the option of 
grouping them if we have more than one hint.
 * specify when the object of the link (or subject when the link is 
reversed as when using security:controller in the examples above) needs to
jump to a new resource. We could start off assuming it always does, when it is not a blank node
 * we may later want some way to specify that a link is inferred by 
reasoning. As what is needed becomes clear, we may find we need a header
more powerful than a link relation. 

RFC 5988 [§2.2 Target Attributes](https://datatracker.ietf.org/doc/html/rfc8288#section-2.2)
defines "Target attributes are a list of key/value pairs that describe the
link or its target". So we can create an attribute "wac_hint" with an optional
value of a number to group sets of hints and order them.

That would work if the nodes we landed on were always URIs, as Link relations require
the subject of a link (which is called the context) to be a URI and the target to be a
URL too. If we use a keyId the final term will always be a URI, but the initial rule 
may not be, and indirect identifications of agents via their key as we saw above make 
a lot of sense.

```Turtle
_:rule1 wac:agent [ cert:key <did:key:z6MkiTBz1ymu...> ]
```
or 
```Turtle
<#rule2> wac:agentGroup [ foaf:member bob:i ]. 
```

where `bob:i` is then related to the keyId one way or another in its own document. 

Another thing to notice is that the Link header allows multiple links between two
resources in one link. But the client giving the hint knows exactly the link to
follow, so it only needs one link. 

#### Creating a new header WAC-Hint

We need to have a way to specify a path of relations with occasional 
intermediary objects, as these can reduces the search space a lot.

Using the [RFC 8941: Structured Field Values](https://datatracker.ietf.org/doc/html/rfc8941)
syntax we can use the [inner list](https://www.rfc-editor.org/rfc/rfc8941.html#name-inner-lists) construct
to encode a path. (Note: sadly RFC8941 does not support URI encoding directly)

##### Simple example following two links

For the `_:rule1` example above we could write up the path as 

```HTTP
WAC-Hint: ( rule "wac:agent";rel "cert:key";rel keyId )
```

Here 
* `rule` is a token to indicate a bnode rule to start off with
* `"wac:agent";rel` is a relation using the registered wac namespace
* `"cert:key";rel` is a relation using the registered cert namespace
* `keyId` refers to the keyId used in the Authentication. 
  If there could be more than one we would want to name them. The keyId URI could also be
  used here as `"<did:key:z6MkiTBz1ymu...>". (todo: find good defaults - e.g. perhaps rel should be the default?)

So the hint is to follow the wac:agent relation on a relevant rule and from 
there the cert:key rule. 

This hint could save searching the `wac:agentGroup` or `wac:accessToClass` spaces for the rule
if those exist. It also tells the Guard that all the data is in the effective WAC rules, so 
there is no need to search outside that space.

##### Example jumping into a new document

If we take the example `<#auth1>` from the top of this document, then a client
could send the following hint

```HTTP
NOTE: '\' line wrapping per RFC 8792

WAC-Hint: ( "<#auth1>" "wac:agent";rel "<https://bblfish.net/people/henry/card#me> \
   "cert:key";rel keyId )
```

This would tell the Guard to start with rule `<#auth1>` follow the relation `wac:agent` to the
WebID `<https://bblfish.net/people/henry/card#me>` and continuing from the location in the remote
graph follow the `cert:key` relation. 

The can reduce the search space down from having to look at the profiles of `n` friends to
just looking for 1. 

#####  Example following three links

For the `_:rule2` example above we could write up the path as 

```HTTP
NOTE: '\' line wrapping per RFC 8792

WAC-Hint: ( "<.acl#r1>" "wac:agentGroup";rel "foaf:member";rel \
   "<https://bob.name/card#i>" \
   "security:controller";rev keyId )
```

Here the hint is that we start with a specific rule given by a relative URL to be resolved 
by the request target and then follow the `wac:agentGroup` relation (to the blank node)
followed by a `foaf:member` relation to bob's WebID (which we then dereference 
by default to continue from bob's WebID profile) and then follow the 
`security:controller` relation in reverse to the `keyId` which we used to authenticate.

The idea is simple: when two relations follow each other we assume there is a blank
node in between them. If a relation is followed by a URL node that is the object
the relation. If that URL is followed by another relation, then it is the subject of 
that new relation. 

##### Complex: Friend of a friend Rule

A major use case for Solid is to allow for decentralised social networks, 
where we could give access to friend of a friend.

A rule that allows friends of friends of timbl to access a resource
could be expressed like this using basic owl constructs.

First we define a foaf relation

```Turtle
foaf:foaf owl:propertyChainAxiom (foaf:knows foaf:knows);
   a rdf:Property, owl:ObjectProperty;
   :label "friend of a friend";
   :comment ”A person known by someone the subject knows. This is not a symmetric property”;
   :domain foaf:Person;
   :range foaf:Person.
```
Next we can define a superproperty of `foaf:knows` and `foaf:foaf` so that we can capture 
people who know each other directly and indirectly. 

```Turtle
# It would be nice if one could state that knows2 was exactly either one of those two.
# Still we don't need more than this

foaf:knows rdfs:subPropertyOf foaf:knows2 .
foaf:foaf rdfs:subPropertyOf foaf:knows2 . 
```
Having defined those we can define a rule that gives Read access to the union
of the previous two groups.

```Turtle
<#foafRule> wac:default <friends/>;
   wac:mode wac:Read;
   wac:agentClass <#foaf> .
    
<#foaf> owl:equivalentClass [ a owl:Restriction ;
    owl:onProperty [ owl:inverseOf foaf:knows2 ];
    owl:hasValue <https://www.w3.org/People/Berners-Lee/#i>;
  ].
```

then a hint could be written like this

```HTTP
NOTE: '\' line wrapping per RFC 8792

WAC-Hint: ( "<.acl#foafRule>" "wac:agentClass";rel "<.acl#foaf>" \
   "rdf:type";rev;reason=r1 \
   "<https://alice.name/#i>" "security:controller";rev keyId )
WAC-Hint: r1=( "<https://www.w3.org/People/Berners-Lee/#i>" "foaf:knows2";rel;reason=r2 "<https://alice.name/#i>")
WAC-Hint: r2=( "<https://www.w3.org/People/Berners-Lee/#i>" "foaf:knows";rel <https://bblfish.net/people/henry/card/#me> \
    "foaf:knows";rel "<https://alice.name/#i>"
```

In the above I am attempting to find a way to giving reasons for a relation separately, in a 
different `WAC-Hint` which decomposes the relation into constituent parts. (This is just
a first draft of an idea). So in the main proof path we have that Alice is of 
type `<.acl#foaf>` which matches what is required of the rule. But we then need to explain why
she is member of that class, and that is because Tim is related to her via `foaf:knows2`. 
That link itself needs to be decomposed into two `foaf:knows` relations which is done in `r2`.

A lot of detail is missing in those rules. But the idea is that we start with the rules
defined in the WAC Document, and follow the links to the key. Sometimes a link we follow can
be proven as being built from smaller links for which we also can find justifying evidence
by following links.

We would like to perhaps simplify the reasoning also. We would like a generic equivalent 
of `<#foaf>` where we could just plug in the original person, and specify that the proof
for that is just to find one or two `foaf:knows` relations.

