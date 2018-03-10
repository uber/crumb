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

import com.uber.crumb.packaging.CrumbLog.Client
import com.uber.crumb.packaging.CrumbLog.Client.DefaultClient
import java.io.PrintWriter
import java.io.StringWriter
import javax.annotation.processing.Messager
import javax.lang.model.element.Element
import javax.tools.Diagnostic.Kind

/**
 * Tiny logger implementation for debugging [CrumbManager], adapted from the
 * [Android DataBinding library](https://android.googlesource.com/platform/frameworks/data-binding/+/master).
 *
 * @param prefix The prefix to use in logs from this logger.
 * @param debugEnabled Whether debug logs are enabled. Default is false.
 * @param client Optional custom [Client] for handling logs. Default is [DefaultClient], which just prints to stdout and stderr.
 */
class CrumbLog(private val prefix: String,
    private val debugEnabled: Boolean = false,
    private val client: Client = DefaultClient) {

  internal fun d(msg: String, vararg args: Any) {
    if (debugEnabled) {
      printMessage(null, Kind.NOTE, String.format(msg, *args))
    }
  }

  internal fun d(element: Element, msg: String, vararg args: Any) {
    if (debugEnabled) {
      printMessage(element, Kind.NOTE, String.format(msg, *args))
    }
  }

  internal fun d(t: Throwable, msg: String, vararg args: Any) {
    if (debugEnabled) {
      printMessage(null, Kind.NOTE,
          String.format(msg, *args) + " " + getStackTrace(t))
    }
  }

  internal fun w(msg: String, vararg args: Any) {
    printMessage(null, Kind.WARNING, String.format(msg, *args))
  }

  internal fun w(element: Element, msg: String, vararg args: Any) {
    printMessage(element, Kind.WARNING, String.format(msg, *args))
  }

  internal fun w(t: Throwable, msg: String, vararg args: Any) {
    printMessage(null, Kind.WARNING,
        String.format(msg, *args) + " " + getStackTrace(t))
  }

  internal fun e(msg: String, vararg args: Any) {
    val fullMsg = String.format(msg, *args)
    printMessage(null, Kind.ERROR, fullMsg)
  }

  internal fun e(element: Element, msg: String, vararg args: Any) {
    val fullMsg = String.format(msg, *args)
    printMessage(element, Kind.ERROR, fullMsg)
  }

  internal fun e(t: Throwable, msg: String, vararg args: Any) {
    val fullMsg = String.format(msg, *args)
    printMessage(null, Kind.ERROR,
        fullMsg + " " + getStackTrace(t))
  }

  private fun printMessage(element: Element?, kind: Kind, message: String) {
    client.printMessage(kind, prefix, message, element)
    if (kind == Kind.ERROR) {
      throw RuntimeException("Crumb failure, see logs for details.\n$message")
    }
  }

  private fun getStackTrace(t: Throwable): String {
    return StringWriter()
        .apply { PrintWriter(this).use(t::printStackTrace) }
        .toString()
  }

  interface Client {
    fun printMessage(kind: Kind, prefix: String, message: String, element: Element?)

    /**
     * Custom [Client] that just writes to a [Messager].
     */
    class MessagerClient(private val messager: Messager) : Client {
      override fun printMessage(kind: Kind, prefix: String, message: String, element: Element?) {
        messager.printMessage(kind, "CrumbLog: $prefix: $message", element)
      }
    }

    /**
     * Default [Client] that just writes to stdout and stderr.
     */
    object DefaultClient : Client {
      override fun printMessage(kind: Kind, prefix: String, message: String, element: Element?) {
        val adjustedMessage = "CrumbLog: $prefix: $message"
        if (kind == Kind.ERROR) {
          System.err.println(adjustedMessage)
        } else {
          println(adjustedMessage)
        }
      }
    }
  }
}
