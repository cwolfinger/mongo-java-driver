/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
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

package com.mongodb.serializers;

import com.mongodb.DBObject;
import com.mongodb.MongoClientTestBase;
import com.mongodb.MongoException;
import com.mongodb.ReflectionDBObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DBObjectSerializerReflectionTest extends MongoClientTestBase {

    @Test
    public void test2() throws MongoException {
        collection.setObjectClass(Outer.class);

        Outer outer = new Outer();
        outer.setName("eliot");

        Inner inner = new Inner();
        inner.setNumber(17);
        outer.setInner(inner);

        collection.insert(outer);

        DBObject obj = collection.findOne();
        assertEquals("eliot", obj.get("Name"));
        assertEquals(Outer.class, obj.getClass());
        outer = (Outer) obj;
        assertEquals("eliot", outer.getName());
        assertEquals(17, outer.getInner().getNumber());
    }


    public static class Outer extends ReflectionDBObject {
        private Inner inner;
        private String name;

        public void setName(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public Inner getInner() {
            return inner;
        }

        public void setInner(final Inner inner) {
            this.inner = inner;
        }
    }

    public static class Inner extends ReflectionDBObject {

        public int number;

        public Inner() {
        }

        public int getNumber() {
            return number;
        }

        public void setNumber(final int number) {
            this.number = number;
        }
    }
}