package com.uber.crumb.extensions

import com.uber.crumb.ExtensionKey

interface CrumbExtension {

  fun key(): ExtensionKey {
    return javaClass.name
  }

}
