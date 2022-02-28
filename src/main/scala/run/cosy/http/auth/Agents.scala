/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import akka.http.scaladsl.model.Uri
import run.cosy.http.auth.KeyIdAgent

import java.security.PublicKey

trait PubKeyIdentified:
   def pubKey: PublicKey

case class KeyIdAgent(keyIdUri: Uri, pubKey: PublicKey)
    extends KeyIdentified, PubKeyIdentified, Agent:
   def keyId: String = keyIdUri.toString()

case class WebIdAgent(webId: Uri) extends Agent
