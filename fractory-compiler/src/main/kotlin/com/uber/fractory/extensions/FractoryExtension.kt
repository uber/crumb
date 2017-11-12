package com.uber.fractory.extensions

interface FractoryExtension {

  fun key(): String {
    return javaClass.name
  }

}
