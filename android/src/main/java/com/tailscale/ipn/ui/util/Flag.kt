// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

/**
 * Code adapted from
 * https://github.com/piashcse/Compose-museum/blob/master/app/src/main/java/com/piashcse/compose_museum/screens/CountryList.kt#L75
 */

// Copyright 2023 piashcse (Mehedi Hassan Piash)
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/** Flag turns an ISO3166 country code into a flag emoji. */
fun String.flag(): String {
  val caps = this.uppercase()
  val flagOffset = 0x1F1E6
  val asciiOffset = 0x41
  val firstChar = Character.codePointAt(caps, 0) - asciiOffset + flagOffset
  val secondChar = Character.codePointAt(caps, 1) - asciiOffset + flagOffset
  return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
}
