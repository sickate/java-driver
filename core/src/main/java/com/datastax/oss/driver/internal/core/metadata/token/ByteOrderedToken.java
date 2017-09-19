/*
 * Copyright (C) 2017-2017 DataStax Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.internal.core.metadata.token;

import com.datastax.oss.driver.api.core.metadata.token.Token;
import com.datastax.oss.protocol.internal.util.Bytes;
import com.google.common.base.Preconditions;
import com.google.common.primitives.UnsignedBytes;
import java.nio.ByteBuffer;

/** A token generated by {@code ByteOrderedPartitioner}. */
public class ByteOrderedToken implements Token {

  private final ByteBuffer value;

  public ByteOrderedToken(ByteBuffer value) {
    this.value = stripTrailingZeroBytes(value);
  }

  public ByteBuffer getValue() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    } else if (other instanceof ByteOrderedToken) {
      ByteOrderedToken that = (ByteOrderedToken) other;
      return this.value.equals(that.getValue());
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public int compareTo(Token other) {
    Preconditions.checkArgument(
        other instanceof ByteOrderedToken, "Can only compare tokens of the same type");
    return UnsignedBytes.lexicographicalComparator()
        .compare(Bytes.getArray(value), Bytes.getArray(((ByteOrderedToken) other).value));
  }

  @Override
  public String toString() {
    return "ByteOrderedToken(" + Bytes.toHexString(value) + ")";
  }

  private static ByteBuffer stripTrailingZeroBytes(ByteBuffer b) {
    byte result[] = Bytes.getArray(b);
    int zeroIndex = result.length;
    for (int i = result.length - 1; i > 0; i--) {
      if (result[i] == 0) {
        zeroIndex = i;
      } else {
        break;
      }
    }
    return ByteBuffer.wrap(result, 0, zeroIndex);
  }
}
