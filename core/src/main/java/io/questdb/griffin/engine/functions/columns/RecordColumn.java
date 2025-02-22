/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.functions.columns;

import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.engine.functions.RecordFunction;

public class RecordColumn extends RecordFunction {
    private final int columnIndex;
    private final RecordMetadata metadata;

    public RecordColumn(int columnIndex, RecordMetadata metadata) {
        this.columnIndex = columnIndex;
        this.metadata = metadata;
    }

    @Override
    public RecordMetadata getMetadata() {
        return metadata;
    }

    @Override
    public Record getRecord(Record rec) {
        return rec.getRecord(columnIndex);
    }

    @Override
    public boolean isReadThreadSafe() {
        return false;
    }

    @Override
    public void toPlan(PlanSink sink) {
        sink.putColumnName(columnIndex);
    }
}
