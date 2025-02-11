// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.sql.common;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.Table;
import com.starrocks.common.util.DebugUtil;
import com.starrocks.connector.PartitionUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.starrocks.sql.optimizer.OptimizerTraceUtil.logMVPrepare;

public final class ListPartitionDiffer extends PartitionDiffer {
    private static final Logger LOG = LogManager.getLogger(ListPartitionDiffer.class);

    /**
     * Iterate srcListMap, if the partition name is not in dstListMap or the partition value is different, add into result.
     *
     * Compare the partition of the base table and the partition of the mv.
     * @param baseItems the partition name to its list partition cell of the base table
     * @param mvItems the partition name to its list partition cell of the mv
     * @return the list partition diff between the base table and the mv
     */
    public static ListPartitionDiff getListPartitionDiff(Map<String, PListCell> baseItems,
                                                         Map<String, PListCell> mvItems) {
        // This synchronization method has a one-to-one correspondence
        // between the base table and the partition of the mv.
        Map<String, PListCell> adds = diffList(baseItems, mvItems);
        Map<String, PListCell> deletes = diffList(mvItems, baseItems);
        return new ListPartitionDiff(adds, deletes);
    }

    /**
     * Iterate srcListMap, if the partition name is not in dstListMap or the partition value is different, add into result.
     * @param srcListMap src partition list map
     * @param dstListMap dst partition list map
     * @return the different partition list map
     */
    public static Map<String, PListCell> diffList(Map<String, PListCell> srcListMap,
                                                  Map<String, PListCell> dstListMap) {
        Map<String, PListCell> result = Maps.newTreeMap();
        // PListCell may contain multi values, we need to ensure they are not duplicated from each other
        Map<PListAtom, PListCell> dstAtomMaps = Maps.newHashMap();
        dstListMap.values().stream()
                .forEach(l -> l.toAtoms().stream().forEach(x -> dstAtomMaps.put(x, l)));
        for (Map.Entry<String, PListCell> srcEntry : srcListMap.entrySet()) {
            String key = srcEntry.getKey();
            PListCell srcItem = srcEntry.getValue();
            if (srcItem.equals(dstListMap.get(key))) {
                continue;
            }
            // distinct atoms
            Set<PListAtom> srcAtoms = srcItem.toAtoms();
            List<PListAtom> srcDistinctAtoms = srcAtoms.stream()
                            .filter(x -> !dstAtomMaps.containsKey(x))
                                    .collect(Collectors.toList());
            if (srcDistinctAtoms.isEmpty()) {
                continue;
            }
            dstAtomMaps.putAll(srcDistinctAtoms.stream().collect(Collectors.toMap(x -> x, x -> srcItem)));
            PListCell newValue =
                    new PListCell(srcDistinctAtoms.stream().map(x -> x.getPartitionItem()).collect(Collectors.toList()));
            result.put(key, newValue);
        }
        return result;
    }

    /**
     * Check if the partition of the base table and the partition of the mv have changed.
     *
     * @param baseListMap the partition name to its list partition cell of the base table
     * @param mvListMap   the partition name to its list partition cell of the mv
     * @return true if the partition has changed, otherwise false
     */
    public static boolean hasListPartitionChanged(Map<String, PListCell> baseListMap,
                                                  Map<String, PListCell> mvListMap) {
        if (checkListPartitionChanged(baseListMap, mvListMap)) {
            return true;
        }
        if (checkListPartitionChanged(mvListMap, baseListMap)) {
            return true;
        }
        return false;
    }

    /**
     * Check if src list map is different from dst list map.
     * @param srcListMap src partition list map
     * @param dstListMap dst partition list map
     * @return true if the partition has changed, otherwise false
     */
    public static boolean checkListPartitionChanged(Map<String, PListCell> srcListMap,
                                                    Map<String, PListCell> dstListMap) {
        for (Map.Entry<String, PListCell> srcEntry : srcListMap.entrySet()) {
            String key = srcEntry.getKey();
            PListCell srcItem = srcEntry.getValue();
            if (!srcItem.equals(dstListMap.get(key))) {
                return true;
            }
        }
        return false;
    }

    private static Map<PListAtom, Set<PListCellPlus>> toAtoms(Map<String, PListCell> partitionMap,
                                                              List<Integer> refIdxes) {
        Map<PListAtom, Set<PListCellPlus>> result = Maps.newHashMap();
        for (Map.Entry<String, PListCell> e : partitionMap.entrySet()) {
            PListCellPlus plus = new PListCellPlus(e.getKey(), e.getValue());
            plus.toAtoms(refIdxes).stream()
                    .forEach(x -> result.computeIfAbsent(x, k -> Sets.newHashSet())
                            .add(new PListCellPlus(e.getKey(), e.getValue())));
        }
        return result;
    }

    private static Map<PListAtom, Set<PListCellPlus>> toAtoms(Map<String, PListCell> partitionMap) {
        Map<PListAtom, Set<PListCellPlus>> result = Maps.newHashMap();
        for (Map.Entry<String, PListCell> e : partitionMap.entrySet()) {
            PListCellPlus plus = new PListCellPlus(e.getKey(), e.getValue());
            plus.toAtoms().stream()
                    .forEach(x -> result.computeIfAbsent(x, k -> Sets.newHashSet())
                            .add(new PListCellPlus(e.getKey(), e.getValue())));
        }
        return result;
    }

    public static Map<String, Set<String>> generateBaseRefMapImpl(Map<PListAtom, Set<PListCellPlus>> mvPartitionMap,
                                                                  Map<String, PListCell> baseTablePartitionMap) {
        if (mvPartitionMap.isEmpty()) {
            return Maps.newHashMap();
        }
        // for each partition of base, find the corresponding partition of mv
        Map<PListAtom, Set<PListCellPlus>> baseAtoms = toAtoms(baseTablePartitionMap);
        Map<String, Set<String>> result = Maps.newHashMap();
        for (Map.Entry<PListAtom, Set<PListCellPlus>> e : baseAtoms.entrySet()) {
            // once base table's singleton is found in mv, add the partition name of mv into result
            PListAtom baseAtom = e.getKey();
            if (mvPartitionMap.containsKey(baseAtom)) {
                Set<PListCellPlus> mvCellPluses = mvPartitionMap.get(baseAtom);
                for (PListCellPlus baseCellPlus : e.getValue()) {
                    mvCellPluses.stream().forEach(x ->
                            result.computeIfAbsent(baseCellPlus.getPartitionName(), k -> Sets.newHashSet())
                                    .add(x.getPartitionName())
                    );
                }
            } else {
                // add an empty set
                Set<PListCellPlus> baseCellPluses = e.getValue();
                baseCellPluses.stream().forEach(x -> result.computeIfAbsent(x.getPartitionName(), k -> Sets.newHashSet()));
            }
        }
        return result;
    }

    /**
     * Collect base table's partition infos.
     * @param basePartitionMaps result to collect base table's partition cells for each table
     * @param allBasePartitionItems result to collect all base table's partition cells(merged)
     * @return true if success, otherwise false
     */
    public static boolean syncBaseTablePartitionInfos(MaterializedView mv,
                                                      Map<Table, Map<String, PListCell>> basePartitionMaps,
                                                      Map<String, PListCell> allBasePartitionItems) {
        Map<Table, List<Column>> refBaseTablePartitionColumns = mv.getRefBaseTablePartitionColumns();
        try {
            for (Map.Entry<Table, List<Column>> e : refBaseTablePartitionColumns.entrySet()) {
                Table refBaseTable = e.getKey();
                List<Column> refPartitionColumns = e.getValue();
                // collect base table's partition cells by aligning with mv's partition column order
                Map<String, PListCell> basePartitionCells = PartitionUtil.getPartitionList(refBaseTable,
                        refPartitionColumns);
                basePartitionMaps.put(refBaseTable, basePartitionCells);

                // merge into a total map to compute the difference
                basePartitionCells.entrySet()
                        .stream()
                        .forEach(x -> allBasePartitionItems.computeIfAbsent(x.getKey(), k -> new PListCell(Lists.newArrayList()))
                                .addItems(x.getValue().getPartitionItems()));
            }
        } catch (Exception e) {
            LOG.warn("Materialized view compute partition difference with base table failed.",
                    DebugUtil.getStackTrace(e));
            return false;
        }
        return true;
    }

    public static ListPartitionDiffResult computeListPartitionDiff(MaterializedView mv,
                                                                   boolean isQueryRewrite) {
        // table -> map<partition name -> partition cell>
        Map<Table, Map<String, PListCell>> refBaseTablePartitionMap = Maps.newHashMap();
        // merge all base table partition cells
        Map<String, PListCell> allBasePartitionItems = Maps.newHashMap();
        if (!syncBaseTablePartitionInfos(mv, refBaseTablePartitionMap, allBasePartitionItems)) {
            logMVPrepare(mv, "Partitioned mv collect base table infos failed");
            return null;
        }
        return computeListPartitionDiff(mv, refBaseTablePartitionMap, allBasePartitionItems, isQueryRewrite);
    }

    public static ListPartitionDiffResult computeListPartitionDiff(
            MaterializedView mv,
            Map<Table, Map<String, PListCell>> refBaseTablePartitionMap,
            Map<String, PListCell> allBasePartitionItems,
            boolean isQueryRewrite) {
        // generate the reference map between the base table and the mv
        // TODO: prune the partitions based on ttl
        Map<String, PListCell> mvPartitionNameToListMap = mv.getListPartitionItems();
        ListPartitionDiff diff = ListPartitionDiffer.getListPartitionDiff(
                allBasePartitionItems, mvPartitionNameToListMap);

        // collect external partition column mapping
        Map<Table, Map<String, Set<String>>> externalPartitionMaps = Maps.newHashMap();
        if (!isQueryRewrite) {
            try {
                collectExternalPartitionNameMapping(mv.getRefBaseTablePartitionColumns(), externalPartitionMaps);
            } catch (Exception e) {
                LOG.warn("Get external partition column mapping failed.", DebugUtil.getStackTrace(e));
                return null;
            }
        }
        return new ListPartitionDiffResult(mvPartitionNameToListMap, refBaseTablePartitionMap, diff, externalPartitionMaps);
    }

    /**
     * Generate the reference map between the base table and the mv.
     * @param basePartitionMaps src partition list map of the base table
     * @param mvPartitionMap mv partition name to its list partition cell
     * @return base table -> <partition name, mv partition names> mapping
     */
    public static Map<Table, Map<String, Set<String>>> generateBaseRefMap(Map<Table, Map<String, PListCell>> basePartitionMaps,
                                                                          Map<String, PListCell> mvPartitionMap) {
        Map<PListAtom, Set<PListCellPlus>> mvAtoms = toAtoms(mvPartitionMap);
        Map<Table, Map<String, Set<String>>> result = Maps.newHashMap();
        for (Map.Entry<Table, Map<String, PListCell>> entry : basePartitionMaps.entrySet()) {
            Table baseTable = entry.getKey();
            Map<String, PListCell> baseTablePartitionMap = entry.getValue();
            Map<String, Set<String>> baseTableRefMap = generateBaseRefMapImpl(mvAtoms, baseTablePartitionMap);
            result.put(baseTable, baseTableRefMap);
        }
        return result;
    }

    /**
     * Generate the reference map between the mv and the base table.
     * @param mvPartitionMap mv partition name to its list partition cell
     * @param basePartitionMaps src partition list map of the base table
     * @return mv partition name -> <base table, base partition names> mapping
     */
    public static  Map<String, Map<Table, Set<String>>> generateMvRefMap(Map<String, PListCell> mvPartitionMap,
                                                                         Map<Table, Map<String, PListCell>> basePartitionMaps) {
        Map<String, Map<Table, Set<String>>> result = Maps.newHashMap();
        // for each partition of base, find the corresponding partition of mv
        Map<PListAtom, Set<PListCellPlus>> mvAtoms = toAtoms(mvPartitionMap);
        for (Map.Entry<Table, Map<String, PListCell>> entry : basePartitionMaps.entrySet()) {
            Table baseTable = entry.getKey();
            Map<String, PListCell> basePartitionMap = entry.getValue();
            Map<PListAtom, Set<PListCellPlus>> baseAtoms = toAtoms(basePartitionMap);
            for (Map.Entry<PListAtom, Set<PListCellPlus>> e : baseAtoms.entrySet()) {
                PListAtom singleton = e.getKey();
                Set<PListCellPlus> baseCellPluses = e.getValue();
                if (mvAtoms.containsKey(singleton)) {
                    Set<PListCellPlus> mvCellPluses = mvAtoms.get(singleton);
                    for (PListCellPlus mvCell : mvCellPluses) {
                        baseCellPluses.stream().forEach(x ->
                                result.computeIfAbsent(mvCell.getPartitionName(), k -> Maps.newHashMap())
                                        .computeIfAbsent(baseTable, k -> Sets.newHashSet())
                                        .add(x.getPartitionName())
                        );
                    }
                }
            }
        }
        return result;
    }
}
