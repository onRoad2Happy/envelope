package com.cloudera.labs.envelope.plan;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.avro.generic.GenericRecord;

import com.cloudera.labs.envelope.RecordModel;
import com.cloudera.labs.envelope.utils.RecordUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * A planner implementation for updating existing and inserting new (upsert). This maintains the
 * most recent version of the values of a key, which is equivalent to Type I SCD modeling.
 */
public class UpsertPlanner extends Planner {

    public UpsertPlanner(Properties props) {
        super(props);
    }
    
    @Override
    public List<PlannedRecord> planMutations(List<GenericRecord> arrivingRecords,
            List<GenericRecord> existingRecords, RecordModel recordModel)
    {
        List<String> keyFieldNames = recordModel.getKeyFieldNames();
        String timestampFieldName = recordModel.getTimestampFieldName();
        List<String> valueFieldNames = recordModel.getValueFieldNames();
        String lastUpdatedFieldName = recordModel.getLastUpdatedFieldName();
        
        Comparator<GenericRecord> tc = new RecordUtils.TimestampComparator(timestampFieldName);
        
        Map<GenericRecord, List<GenericRecord>> arrivedByKey = RecordUtils.recordsByKey(arrivingRecords, keyFieldNames);
        Map<GenericRecord, List<GenericRecord>> existingByKey = RecordUtils.recordsByKey(existingRecords, keyFieldNames);
        
        List<PlannedRecord> planned = Lists.newArrayList();
        
        for (Map.Entry<GenericRecord, List<GenericRecord>> arrivingByKey : arrivedByKey.entrySet()) {
            GenericRecord key = arrivingByKey.getKey();
            List<GenericRecord> arrivingForKey = arrivingByKey.getValue();
            List<GenericRecord> existingForKey = existingByKey.get(key);
            
            if (arrivingForKey.size() > 1) {
                Collections.sort(arrivingForKey, Collections.reverseOrder(tc));
            }
            GenericRecord arrived = arrivingForKey.get(0);
            
            GenericRecord existing = null;
            if (existingForKey != null) {
                existing = existingForKey.get(0);
            }
            
            if (existing == null) {
                if (recordModel.hasLastUpdatedField()) {
                    arrived.put(lastUpdatedFieldName, currentTimestampString());
                }
                planned.add(new PlannedRecord(arrived, MutationType.INSERT));
            }
            else if (RecordUtils.before(arrived, existing, timestampFieldName)) {
                // We do nothing because the arriving record is older than the existing record
            }
            else if ((RecordUtils.simultaneous(arrived, existing, timestampFieldName) ||
                      RecordUtils.after(arrived, existing, timestampFieldName)) &&
                     RecordUtils.different(arrived, existing, valueFieldNames))
            {
                if (recordModel.hasLastUpdatedField()) {
                    arrived.put(lastUpdatedFieldName, currentTimestampString());
                }
                planned.add(new PlannedRecord(arrived, MutationType.UPDATE));
            }
        }
        
        return planned;
    }

    @Override
    public boolean requiresExistingRecords() {
        return true;
    }

    @Override
    public boolean requiresKeyColocation() {
        return true;
    }

    @Override
    public Set<MutationType> getEmittedMutationTypes() {
        return Sets.newHashSet(MutationType.INSERT, MutationType.UPDATE);
    }

}
