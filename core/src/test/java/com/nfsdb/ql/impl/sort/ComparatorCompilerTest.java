/*******************************************************************************
 *  _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 *
 * Copyright (c) 2014-2016. The NFSdb project and its contributors.
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
 ******************************************************************************/

package com.nfsdb.ql.impl.sort;

import com.nfsdb.JournalEntryWriter;
import com.nfsdb.JournalWriter;
import com.nfsdb.factory.configuration.AbstractRecordMetadata;
import com.nfsdb.factory.configuration.JournalStructure;
import com.nfsdb.factory.configuration.RecordColumnMetadata;
import com.nfsdb.misc.Unsafe;
import com.nfsdb.ql.RecordSource;
import com.nfsdb.ql.StorageFacade;
import com.nfsdb.ql.ops.AbstractVirtualColumn;
import com.nfsdb.ql.parser.AbstractOptimiserTest;
import com.nfsdb.std.IntList;
import com.nfsdb.std.ObjList;
import com.nfsdb.store.ColumnType;
import com.nfsdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Test;
import sun.invoke.anon.AnonymousClassLoader;

public class ComparatorCompilerTest extends AbstractOptimiserTest {

    private final ComparatorCompiler cc = new ComparatorCompiler();

    @Test
    public void testAllGetters() throws Exception {
        JournalWriter w = factory.writer(new JournalStructure("xyz")
                .$bool("bool")
                .$byte("byte")
                .$double("double")
                .$float("float")
                .$int("int")
                .$long("long")
                .$date("date")
                .$short("short")
                .$str("str")
                .$sym("sym")
                .$());

        JournalEntryWriter ew = w.entryWriter();

        ew.putBool(0, true);
        ew.put(1, (byte) 13);
        ew.putDouble(2, 20.12);
        ew.putFloat(3, 10.15f);
        ew.putInt(4, 4);
        ew.putLong(5, 9988908080988890L);
        ew.putDate(6, 88979879L);
        ew.putShort(7, (short) 902);
        ew.putStr(8, "complexity made simple");
        ew.putSym(9, "nfsdb");
        ew.append();

        ew = w.entryWriter();
        ew.put(1, (byte) 13);
        ew.putDouble(2, 20.12);
        ew.putFloat(3, 10.15f);
        ew.putInt(4, 4);
        ew.putLong(5, 9988908080988890L);
        ew.putDate(6, 88979879L);
        ew.putShort(7, (short) 902);
        ew.putStr(8, "complexity made simple");
        ew.putSym(9, "appsicle");
        ew.append();

        w.commit();
        w.close();

        IntList indices = new IntList();
        for (int i = 0, n = w.getMetadata().getColumnCount(); i < n; i++) {
            indices.add(i);
        }
        RecordSource rs = compiler.compileSource(factory, "xyz");
        RecordComparator rc = cc.compile(AnonymousClassLoader.make(Unsafe.getUnsafe(), ComparatorCompilerTest.class), rs.getMetadata(), indices);
        RBTreeSortedRecordSource map = new RBTreeSortedRecordSource(16 * 1024 * 1024, rs, rc);

        sink.clear();
        printer.printCursor(map.prepareCursor(factory));
        TestUtils.assertEquals(
                "false\t13\t20.120000000000\t10.1500\t4\t9988908080988890\t1970-01-02T00:42:59.879Z\t902\tcomplexity made simple\tappsicle\n" +
                        "true\t13\t20.120000000000\t10.1500\t4\t9988908080988890\t1970-01-02T00:42:59.879Z\t902\tcomplexity made simple\tnfsdb\n",
                sink);
    }

    @Test
    public void testCompileAll() throws Exception {
        TestRecordMetadata m = new TestRecordMetadata().addDistinct();
        IntList indices = new IntList(m.getColumnCount());
        for (int i = 0, n = m.getColumnCount(); i < n; i++) {
            indices.add(i);
        }
        RecordComparator rc = cc.compile(AnonymousClassLoader.make(Unsafe.getUnsafe(), ComparatorCompilerTest.class), m, indices);
        Assert.assertNotNull(rc);
    }

    @Test
    public void testCompileLarge() throws Exception {
        TestRecordMetadata m = new TestRecordMetadata();
        for (int i = 0; i < 155; i++) {
            m.addDistinct();
        }
        IntList indices = new IntList(m.getColumnCount());
        for (int i = 0, n = m.getColumnCount(); i < n; i++) {
            indices.add(i);
        }
        RecordComparator rc = cc.compile(AnonymousClassLoader.make(Unsafe.getUnsafe(), ComparatorCompilerTest.class), m, indices);
        Assert.assertNotNull(rc);
    }

    @Test
    public void testCompileMultipleOfSame() throws Exception {
        TestRecordMetadata m = new TestRecordMetadata();
        for (int i = 0; i < 155; i++) {
            m._type(ColumnType.STRING);
        }
        IntList indices = new IntList(m.getColumnCount());
        for (int i = 0, n = m.getColumnCount(); i < n; i++) {
            indices.add(i);
        }
        RecordComparator rc = cc.compile(AnonymousClassLoader.make(Unsafe.getUnsafe(), ComparatorCompilerTest.class), m, indices);
        Assert.assertNotNull(rc);
    }

    @Test
    public void testTwoClassesSameClassloader() throws Exception {
        TestRecordMetadata m = new TestRecordMetadata();
        for (int i = 0; i < 155; i++) {
            m._type(ColumnType.STRING);
        }
        IntList indices = new IntList(m.getColumnCount());
        for (int i = 0, n = m.getColumnCount(); i < n; i++) {
            indices.add(i);
        }
        AnonymousClassLoader cl = AnonymousClassLoader.make(Unsafe.getUnsafe(), ComparatorCompilerTest.class);
        RecordComparator rc1 = cc.compile(cl, m, indices);
        RecordComparator rc2 = cc.compile(cl, m, indices);

        Assert.assertTrue(rc1 != rc2);
        Assert.assertNotNull(rc1);
        Assert.assertNotNull(rc2);
    }

    private static class TestColumnMetadata extends AbstractVirtualColumn {

        public TestColumnMetadata(ColumnType type) {
            super(type);
        }

        @Override
        public boolean isConstant() {
            return false;
        }

        @Override
        public void prepare(StorageFacade facade) {

        }
    }

    private static class TestRecordMetadata extends AbstractRecordMetadata {
        private final ObjList<TestColumnMetadata> columns = new ObjList<>();

        public TestRecordMetadata() {
        }

        public void _type(ColumnType t) {
            columns.add(new TestColumnMetadata(t));
        }

        public TestRecordMetadata addDistinct() {
            _type(ColumnType.BOOLEAN);
            _type(ColumnType.BYTE);
            _type(ColumnType.DOUBLE);
            _type(ColumnType.FLOAT);
            _type(ColumnType.INT);
            _type(ColumnType.LONG);
            _type(ColumnType.DATE);
            _type(ColumnType.SHORT);
            _type(ColumnType.STRING);
            _type(ColumnType.SYMBOL);
            return this;
        }

        @Override
        public RecordColumnMetadata getColumn(int index) {
            return columns.get(index);
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public int getColumnIndexQuiet(CharSequence name) {
            return 0;
        }

        @Override
        public RecordColumnMetadata getColumnQuick(int index) {
            return columns.getQuick(index);
        }

        @Override
        public int getTimestampIndex() {
            return -1;
        }
    }
}