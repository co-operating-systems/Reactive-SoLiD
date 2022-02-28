/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import com.typesafe.config.{Config, ConfigFactory}
import run.cosy.Solid

import java.nio.file.Files
import scala.io.StdIn

def main(args: Array[String]): Unit =
   val path = java.nio.file.Paths.get(args(0))

   /** see
     * [[https://doc.akka.io/docs/akka/current/typed/logging.html#dependency Akka Logging Config doc]]
     * for more details
     */
   val testConf: Config = ConfigFactory.parseString("""
                                                      |akka.loglevel = "DEBUG"
                                                      |akka.log-config-on-start_off = on
                                                      |akka.log-dead-letters = off
    """.stripMargin)

   if Files.exists(path) && Files.isDirectory(path) then
      val sys = ActorSystem(Solid(Uri("http://localhost:8080/"), path), "Reactive-Solid", testConf)
      println(s"Server online at http://localhost:8080/\nPress any character + RETURN to stop...")
      while StdIn.readLine().isEmpty do
         println("-------------")
      sys.terminate()
   else
      println(s"Could not find directory <$path>")
