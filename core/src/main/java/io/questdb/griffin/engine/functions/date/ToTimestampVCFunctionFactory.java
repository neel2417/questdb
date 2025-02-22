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

package io.questdb.griffin.engine.functions.date;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.Record;
import io.questdb.griffin.FunctionFactory;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.functions.TimestampFunction;
import io.questdb.griffin.engine.functions.UnaryFunction;
import io.questdb.griffin.engine.functions.constants.TimestampConstant;
import io.questdb.std.IntList;
import io.questdb.std.Numbers;
import io.questdb.std.NumericException;
import io.questdb.std.ObjList;
import io.questdb.std.datetime.DateFormat;
import io.questdb.std.datetime.DateLocale;
import io.questdb.std.datetime.microtime.TimestampFormatCompiler;

public class ToTimestampVCFunctionFactory implements FunctionFactory {
    private static final ThreadLocal<TimestampFormatCompiler> tlCompiler = ThreadLocal.withInitial(TimestampFormatCompiler::new);

    @Override
    public String getSignature() {
        return "to_timestamp(Ss)";
    }

    @Override
    public Function newInstance(
            int position,
            ObjList<Function> args,
            IntList argPositions,
            CairoConfiguration configuration,
            SqlExecutionContext sqlExecutionContext
    ) throws SqlException {
        final Function arg = args.getQuick(0);
        final CharSequence pattern = args.getQuick(1).getStr(null);
        if (pattern == null) {
            throw SqlException.$(argPositions.getQuick(1), "pattern is required");
        }
        if (arg.isConstant()) {
            return evaluateConstant(arg, tlCompiler.get().compile(pattern), configuration.getDefaultDateLocale());
        } else {
            return new Func(arg, tlCompiler.get().compile(pattern), configuration.getDefaultDateLocale());
        }
    }

    private TimestampConstant evaluateConstant(Function arg, DateFormat timestampFormat, DateLocale locale) {
        CharSequence value = arg.getStr(null);
        try {
            if (value != null) {
                return new TimestampConstant(timestampFormat.parse(value, locale));
            }
        } catch (NumericException ignore) {
        }

        return TimestampConstant.NULL;
    }

    private static final class Func extends TimestampFunction implements UnaryFunction {

        private final Function arg;
        private final DateLocale locale;
        private final DateFormat timestampFormat;

        public Func(Function arg, DateFormat timestampFormat, DateLocale locale) {
            this.arg = arg;
            this.timestampFormat = timestampFormat;
            this.locale = locale;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public long getTimestamp(Record rec) {
            CharSequence value = arg.getStr(rec);
            try {
                if (value != null) {
                    return timestampFormat.parse(value, locale);
                }
            } catch (NumericException ignore) {
            }
            return Numbers.LONG_NaN;
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.val("to_timestamp(").val(arg).val(')');
        }
    }
}
