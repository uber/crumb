/*
 * Copyright (c) 2018. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.crumb.packaging

import java.io.PrintWriter
import java.io.StringWriter

import javax.lang.model.element.Element
import javax.tools.Diagnostic.Kind

/**
 * Tiny logger implementation for debugging, borrowed from databinding.
 */
internal object CrumbLog {

  var isDebugEnabled = false
  private val SYSTEM_CLIENT: Client = object : Client {
    override fun printMessage(kind: Kind, message: String, element: Element?) {
      if (kind == Kind.ERROR) {
        System.err.println(message)
      } else {
        println(message)
      }
    }
  }

  private var sClient = SYSTEM_CLIENT

  fun setClient(systemClient: Client) {
    sClient = systemClient
  }

  fun setDebugLog(enabled: Boolean) {
    isDebugEnabled = enabled
  }

  fun d(msg: String, vararg args: Any) {
    if (isDebugEnabled) {
      printMessage(null, Kind.NOTE, String.format(msg, *args))
    }
  }

  fun d(element: Element, msg: String, vararg args: Any) {
    if (isDebugEnabled) {
      printMessage(element, Kind.NOTE, String.format(msg, *args))
    }
  }

  fun d(t: Throwable, msg: String, vararg args: Any) {
    if (isDebugEnabled) {
      printMessage(null, Kind.NOTE,
          String.format(msg, *args) + " " + getStackTrace(t))
    }
  }

  fun w(msg: String, vararg args: Any) {
    printMessage(null, Kind.WARNING, String.format(msg, *args))
  }

  fun w(element: Element, msg: String, vararg args: Any) {
    printMessage(element, Kind.WARNING, String.format(msg, *args))
  }

  fun w(t: Throwable, msg: String, vararg args: Any) {
    printMessage(null, Kind.WARNING,
        String.format(msg, *args) + " " + getStackTrace(t))
  }

  fun e(msg: String, vararg args: Any) {
    val fullMsg = String.format(msg, *args)
    printMessage(null, Kind.ERROR, fullMsg)
  }

  fun e(element: Element, msg: String, vararg args: Any) {
    val fullMsg = String.format(msg, *args)
    printMessage(element, Kind.ERROR, fullMsg)
  }

  fun e(t: Throwable, msg: String, vararg args: Any) {
    val fullMsg = String.format(msg, *args)
    printMessage(null, Kind.ERROR,
        fullMsg + " " + getStackTrace(t))
  }

  private fun printMessage(element: Element?, kind: Kind, message: String) {
    sClient.printMessage(kind, message, element)
    if (kind == Kind.ERROR) {
      throw RuntimeException("failure, see logs for details.\n" + message)
    }
  }

  private fun getStackTrace(t: Throwable): String {
    val sw = StringWriter()
    PrintWriter(sw).use { pw -> t.printStackTrace(pw) }
    return sw.toString()
  }

  interface Client {
    fun printMessage(kind: Kind, message: String, element: Element?)
  }
}
