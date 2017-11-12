package com.uber.fractory.extensions

import com.uber.fractory.ExtensionKey

interface FractoryExtension {

  fun key(): ExtensionKey {
    return javaClass.name
  }

}
