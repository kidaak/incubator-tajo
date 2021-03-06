/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.engine.planner;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.tajo.TajoTestingCluster;
import org.apache.tajo.algebra.Expr;
import org.apache.tajo.algebra.JoinType;
import org.apache.tajo.benchmark.TPCH;
import org.apache.tajo.catalog.*;
import org.apache.tajo.catalog.proto.CatalogProtos.FunctionType;
import org.apache.tajo.catalog.proto.CatalogProtos.StoreType;
import org.apache.tajo.common.TajoDataTypes.Type;
import org.apache.tajo.engine.eval.EvalType;
import org.apache.tajo.engine.function.builtin.SumInt;
import org.apache.tajo.engine.json.CoreGsonHelper;
import org.apache.tajo.engine.parser.SQLAnalyzer;
import org.apache.tajo.engine.planner.logical.*;
import org.apache.tajo.master.TajoMaster;
import org.apache.tajo.util.CommonTestingUtil;
import org.apache.tajo.util.FileUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.junit.Assert.*;

public class TestLogicalPlanner {
  private static TajoTestingCluster util;
  private static CatalogService catalog;
  private static SQLAnalyzer sqlAnalyzer;
  private static LogicalPlanner planner;
  private static TPCH tpch;

  @BeforeClass
  public static void setUp() throws Exception {
    util = new TajoTestingCluster();
    util.startCatalogCluster();
    catalog = util.getMiniCatalogCluster().getCatalog();
    for (FunctionDesc funcDesc : TajoMaster.initBuiltinFunctions()) {
      catalog.createFunction(funcDesc);
    }

    Schema schema = new Schema();
    schema.addColumn("name", Type.TEXT);
    schema.addColumn("empid", Type.INT4);
    schema.addColumn("deptname", Type.TEXT);

    Schema schema2 = new Schema();
    schema2.addColumn("deptname", Type.TEXT);
    schema2.addColumn("manager", Type.TEXT);

    Schema schema3 = new Schema();
    schema3.addColumn("deptname", Type.TEXT);
    schema3.addColumn("score", Type.INT4);

    TableMeta meta = CatalogUtil.newTableMeta(StoreType.CSV);
    TableDesc people = new TableDesc("employee", schema, meta, CommonTestingUtil.getTestDir());
    catalog.addTable(people);

    TableDesc student = new TableDesc("dept", schema2, StoreType.CSV, new Options(), CommonTestingUtil.getTestDir());
    catalog.addTable(student);

    TableDesc score = new TableDesc("score", schema3, StoreType.CSV, new Options(), CommonTestingUtil.getTestDir());
    catalog.addTable(score);

    FunctionDesc funcDesc = new FunctionDesc("sumtest", SumInt.class, FunctionType.AGGREGATION,
        CatalogUtil.newSimpleDataType(Type.INT4),
        CatalogUtil.newSimpleDataTypeArray(Type.INT4));


    // TPC-H Schema for Complex Queries
    String [] tpchTables = {
        "part", "supplier", "partsupp", "nation", "region", "lineitem"
    };
    tpch = new TPCH();
    tpch.loadSchemas();
    tpch.loadOutSchema();
    for (String table : tpchTables) {
      TableMeta m = CatalogUtil.newTableMeta(StoreType.CSV);
      TableDesc d = CatalogUtil.newTableDesc(table, tpch.getSchema(table), m, CommonTestingUtil.getTestDir());
      catalog.addTable(d);
    }

    catalog.createFunction(funcDesc);
    sqlAnalyzer = new SQLAnalyzer();
    planner = new LogicalPlanner(catalog);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    util.shutdownCatalogCluster();
  }

  static String[] QUERIES = {
      "select name, empid, deptname from employee where empId > 500", // 0
      "select name, empid, e.deptname, manager from employee as e, dept as dp", // 1
      "select name, empid, e.deptname, manager, score from employee as e, dept, score", // 2
      "select p.deptname, sumtest(score) from dept as p, score group by p.deptName having sumtest(score) > 30", // 3
      "select p.deptname, score from dept as p, score order by score asc", // 4
      "select name from employee where empId = 100", // 5
      "select name, score from employee, score", // 6
      "select p.deptName, sumtest(score) from dept as p, score group by p.deptName", // 7
      "create table store1 as select p.deptName, sumtest(score) from dept as p, score group by p.deptName", // 8
      "select deptName, sumtest(score) from score group by deptName having sumtest(score) > 30", // 9
      "select 7 + 8 as res1, 8 * 9 as res2, 10 * 10 as res3", // 10
      "create index idx_employee on employee using bitmap (name null first, empId desc) with ('fillfactor' = 70)", // 11
      "select name, score from employee, score order by score limit 3", // 12
      "select length(name), length(deptname), *, empid+10 from employee where empId > 500", // 13
  };

  @Test
  public final void testSingleRelation() throws CloneNotSupportedException, PlanningException {
    Expr expr = sqlAnalyzer.parse(QUERIES[0]);
    LogicalPlan planNode = planner.createPlan(expr);
    LogicalNode plan = planNode.getRootBlock().getRoot();
    assertEquals(NodeType.ROOT, plan.getType());
    TestLogicalNode.testCloneLogicalNode(plan);
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);

    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    ProjectionNode projNode = root.getChild();

    assertEquals(NodeType.SELECTION, projNode.getChild().getType());
    SelectionNode selNode = projNode.getChild();

    assertEquals(NodeType.SCAN, selNode.getChild().getType());
    ScanNode scanNode = selNode.getChild();
    assertEquals("employee", scanNode.getTableName());
  }

  public static void assertSchema(Schema expected, Schema schema) {
    Column expectedColumn;
    Column column;
    for (int i = 0; i < expected.size(); i++) {
      expectedColumn = expected.getColumn(i);
      column = schema.getColumn(expectedColumn.getSimpleName());
      assertEquals(expectedColumn.getSimpleName(), column.getSimpleName());
      assertEquals(expectedColumn.getDataType(), column.getDataType());
    }
  }

  @Test
  public final void testImplicityJoinPlan() throws CloneNotSupportedException, PlanningException {
    // two relations
    Expr expr = sqlAnalyzer.parse(QUERIES[1]);
    LogicalPlan planNode = planner.createPlan(expr);
    LogicalNode plan = planNode.getRootBlock().getRoot();

    assertEquals(NodeType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);
    TestLogicalNode.testCloneLogicalNode(root);

    Schema expectedSchema = new Schema();
    expectedSchema.addColumn("name", Type.TEXT);
    expectedSchema.addColumn("empid", Type.INT4);
    expectedSchema.addColumn("deptname", Type.TEXT);
    expectedSchema.addColumn("manager", Type.TEXT);
    for (int i = 0; i < expectedSchema.size(); i++) {
      Column found = root.getOutSchema().getColumn(expectedSchema.getColumn(i).getSimpleName());
      assertEquals(expectedSchema.getColumn(i).getDataType(), found.getDataType());
    }

    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    ProjectionNode projNode = root.getChild();

    assertEquals(NodeType.JOIN, projNode.getChild().getType());
    JoinNode joinNode = projNode.getChild();

    assertEquals(NodeType.SCAN, joinNode.getLeftChild().getType());
    ScanNode leftNode = joinNode.getLeftChild();
    assertEquals("employee", leftNode.getTableName());
    assertEquals(NodeType.SCAN, joinNode.getRightChild().getType());
    ScanNode rightNode = joinNode.getRightChild();
    assertEquals("dept", rightNode.getTableName());

    // three relations
    expr = sqlAnalyzer.parse(QUERIES[2]);
    plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    TestLogicalNode.testCloneLogicalNode(plan);

    expectedSchema.addColumn("score", Type.INT4);
    assertSchema(expectedSchema, plan.getOutSchema());

    assertEquals(NodeType.ROOT, plan.getType());
    root = (LogicalRootNode) plan;

    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    projNode = root.getChild();

    assertEquals(NodeType.JOIN, projNode.getChild().getType());
    joinNode = projNode.getChild();

    assertEquals(NodeType.JOIN, joinNode.getLeftChild().getType());

    assertEquals(NodeType.SCAN, joinNode.getRightChild().getType());
    ScanNode scan1 = joinNode.getRightChild();
    assertEquals("score", scan1.getTableName());

    JoinNode leftNode2 = joinNode.getLeftChild();
    assertEquals(NodeType.JOIN, leftNode2.getType());

    assertEquals(NodeType.SCAN, leftNode2.getLeftChild().getType());
    ScanNode leftScan = leftNode2.getLeftChild();
    assertEquals("employee", leftScan.getTableName());

    assertEquals(NodeType.SCAN, leftNode2.getRightChild().getType());
    ScanNode rightScan = leftNode2.getRightChild();
    assertEquals("dept", rightScan.getTableName());
  }



  String [] JOINS = {
      "select name, dept.deptName, score from employee natural join dept natural join score", // 0
      "select name, dept.deptName, score from employee inner join dept on employee.deptName = dept.deptName inner join score on dept.deptName = score.deptName", // 1
      "select name, dept.deptName, score from employee left outer join dept on employee.deptName = dept.deptName right outer join score on dept.deptName = score.deptName" // 2
  };

  static Schema expectedJoinSchema;
  static {
    expectedJoinSchema = new Schema();
    expectedJoinSchema.addColumn("name", Type.TEXT);
    expectedJoinSchema.addColumn("deptName", Type.TEXT);
    expectedJoinSchema.addColumn("score", Type.INT4);
  }

  @Test
  public final void testNaturalJoinPlan() throws PlanningException {
    // two relations
    Expr context = sqlAnalyzer.parse(JOINS[0]);
    LogicalNode plan = planner.createPlan(context).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertSchema(expectedJoinSchema, plan.getOutSchema());

    assertEquals(NodeType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    ProjectionNode proj = root.getChild();
    assertEquals(NodeType.JOIN, proj.getChild().getType());
    JoinNode join = proj.getChild();
    assertEquals(JoinType.INNER, join.getJoinType());
    assertEquals(NodeType.SCAN, join.getRightChild().getType());
    assertTrue(join.hasJoinQual());
    ScanNode scan = join.getRightChild();
    assertEquals("score", scan.getTableName());

    assertEquals(NodeType.JOIN, join.getLeftChild().getType());
    join = join.getLeftChild();
    assertEquals(JoinType.INNER, join.getJoinType());
    assertEquals(NodeType.SCAN, join.getLeftChild().getType());
    ScanNode outer = join.getLeftChild();
    assertEquals("employee", outer.getTableName());
    assertEquals(NodeType.SCAN, join.getRightChild().getType());
    ScanNode inner = join.getRightChild();
    assertEquals("dept", inner.getTableName());
  }

  @Test
  public final void testInnerJoinPlan() throws PlanningException {
    // two relations
    Expr expr = sqlAnalyzer.parse(JOINS[1]);
    LogicalPlan plan = planner.createPlan(expr);
    LogicalNode root = plan.getRootBlock().getRoot();
    testJsonSerDerObject(root);
    assertSchema(expectedJoinSchema, root.getOutSchema());

    assertEquals(NodeType.ROOT, root.getType());
    assertEquals(NodeType.PROJECTION, ((LogicalRootNode)root).getChild().getType());
    ProjectionNode proj = ((LogicalRootNode)root).getChild();
    assertEquals(NodeType.JOIN, proj.getChild().getType());
    JoinNode join = proj.getChild();
    assertEquals(JoinType.INNER, join.getJoinType());
    assertEquals(NodeType.SCAN, join.getRightChild().getType());
    ScanNode scan = join.getRightChild();
    assertEquals("score", scan.getTableName());

    assertEquals(NodeType.JOIN, join.getLeftChild().getType());
    join = join.getLeftChild();
    assertEquals(JoinType.INNER, join.getJoinType());
    assertEquals(NodeType.SCAN, join.getLeftChild().getType());
    ScanNode outer = join.getLeftChild();
    assertEquals("employee", outer.getTableName());
    assertEquals(NodeType.SCAN, join.getRightChild().getType());
    ScanNode inner = join.getRightChild();
    assertEquals("dept", inner.getTableName());
    assertTrue(join.hasJoinQual());
    assertEquals(EvalType.EQUAL, join.getJoinQual().getType());
  }

  @Test
  public final void testOuterJoinPlan() throws PlanningException {
    // two relations
    Expr expr = sqlAnalyzer.parse(JOINS[2]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertSchema(expectedJoinSchema, plan.getOutSchema());

    assertEquals(NodeType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    ProjectionNode proj = root.getChild();
    assertEquals(NodeType.JOIN, proj.getChild().getType());
    JoinNode join = proj.getChild();
    assertEquals(JoinType.RIGHT_OUTER, join.getJoinType());
    assertEquals(NodeType.SCAN, join.getRightChild().getType());
    ScanNode scan = join.getRightChild();
    assertEquals("score", scan.getTableName());

    assertEquals(NodeType.JOIN, join.getLeftChild().getType());
    join = join.getLeftChild();
    assertEquals(JoinType.LEFT_OUTER, join.getJoinType());
    assertEquals(NodeType.SCAN, join.getLeftChild().getType());
    ScanNode outer = join.getLeftChild();
    assertEquals("employee", outer.getTableName());
    assertEquals(NodeType.SCAN, join.getRightChild().getType());
    ScanNode inner = join.getRightChild();
    assertEquals("dept", inner.getTableName());
    assertTrue(join.hasJoinQual());
    assertEquals(EvalType.EQUAL, join.getJoinQual().getType());
  }


  @Test
  public final void testGroupby() throws CloneNotSupportedException, PlanningException {
    // without 'having clause'
    Expr context = sqlAnalyzer.parse(QUERIES[7]);
    LogicalNode plan = planner.createPlan(context).getRootBlock().getRoot();

    assertEquals(NodeType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);
    testQuery7(root.getChild());

    // with having clause
    context = sqlAnalyzer.parse(QUERIES[3]);
    plan = planner.createPlan(context).getRootBlock().getRoot();
    TestLogicalNode.testCloneLogicalNode(plan);

    assertEquals(NodeType.ROOT, plan.getType());
    root = (LogicalRootNode) plan;

    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    ProjectionNode projNode = root.getChild();
    assertEquals(NodeType.HAVING, projNode.getChild().getType());
    HavingNode havingNode = projNode.getChild();
    assertEquals(NodeType.GROUP_BY, havingNode.getChild().getType());
    GroupbyNode groupByNode =  havingNode.getChild();

    assertEquals(NodeType.JOIN, groupByNode.getChild().getType());
    JoinNode joinNode = groupByNode.getChild();

    assertEquals(NodeType.SCAN, joinNode.getLeftChild().getType());
    ScanNode leftNode = joinNode.getLeftChild();
    assertEquals("dept", leftNode.getTableName());
    assertEquals(NodeType.SCAN, joinNode.getRightChild().getType());
    ScanNode rightNode = joinNode.getRightChild();
    assertEquals("score", rightNode.getTableName());

    //LogicalOptimizer.optimize(context, plan);
  }


  @Test
  public final void testMultipleJoin() throws IOException, PlanningException {
    Expr expr = sqlAnalyzer.parse(
        FileUtil.readTextFile(new File("src/test/resources/queries/TestJoinQuery/testTPCHQ2Join.sql")));
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    Schema expected = tpch.getOutSchema("q2");
    assertSchema(expected, plan.getOutSchema());
  }


  static void testQuery7(LogicalNode plan) {
    assertEquals(NodeType.PROJECTION, plan.getType());
    ProjectionNode projNode = (ProjectionNode) plan;
    assertEquals(NodeType.GROUP_BY, projNode.getChild().getType());
    GroupbyNode groupByNode = projNode.getChild();

    assertEquals(NodeType.JOIN, groupByNode.getChild().getType());
    JoinNode joinNode = groupByNode.getChild();

    assertEquals(NodeType.SCAN, joinNode.getLeftChild().getType());
    ScanNode leftNode = joinNode.getLeftChild();
    assertEquals("dept", leftNode.getTableName());
    assertEquals(NodeType.SCAN, joinNode.getRightChild().getType());
    ScanNode rightNode = joinNode.getRightChild();
    assertEquals("score", rightNode.getTableName());
  }


  @Test
  public final void testStoreTable() throws CloneNotSupportedException, PlanningException {
    Expr context = sqlAnalyzer.parse(QUERIES[8]);
    LogicalNode plan = planner.createPlan(context).getRootBlock().getRoot();
    TestLogicalNode.testCloneLogicalNode(plan);
    testJsonSerDerObject(plan);

    assertEquals(NodeType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;

    assertEquals(NodeType.CREATE_TABLE, root.getChild().getType());
    StoreTableNode storeNode = root.getChild();
    testQuery7(storeNode.getChild());
  }

  @Test
  public final void testOrderBy() throws CloneNotSupportedException, PlanningException {
    Expr expr = sqlAnalyzer.parse(QUERIES[4]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    TestLogicalNode.testCloneLogicalNode(plan);

    assertEquals(NodeType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;

    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    ProjectionNode projNode = root.getChild();

    assertEquals(NodeType.SORT, projNode.getChild().getType());
    SortNode sortNode = projNode.getChild();

    assertEquals(NodeType.JOIN, sortNode.getChild().getType());
    JoinNode joinNode = sortNode.getChild();

    assertEquals(NodeType.SCAN, joinNode.getLeftChild().getType());
    ScanNode leftNode = joinNode.getLeftChild();
    assertEquals("dept", leftNode.getTableName());
    assertEquals(NodeType.SCAN, joinNode.getRightChild().getType());
    ScanNode rightNode = joinNode.getRightChild();
    assertEquals("score", rightNode.getTableName());
  }

  @Test
  public final void testLimit() throws CloneNotSupportedException, PlanningException {
    Expr expr = sqlAnalyzer.parse(QUERIES[12]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    TestLogicalNode.testCloneLogicalNode(plan);

    assertEquals(NodeType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;

    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    ProjectionNode projNode = root.getChild();

    assertEquals(NodeType.LIMIT, projNode.getChild().getType());
    LimitNode limitNode = projNode.getChild();

    assertEquals(NodeType.SORT, limitNode.getChild().getType());
  }

  @Test
  public final void testSPJPush() throws CloneNotSupportedException, PlanningException {
    Expr expr = sqlAnalyzer.parse(QUERIES[5]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    TestLogicalNode.testCloneLogicalNode(plan);

    assertEquals(NodeType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    ProjectionNode projNode = root.getChild();
    assertEquals(NodeType.SELECTION, projNode.getChild().getType());
    SelectionNode selNode = projNode.getChild();
    assertEquals(NodeType.SCAN, selNode.getChild().getType());
    ScanNode scanNode = selNode.getChild();
    assertEquals(scanNode.getTableName(), "employee");
  }



  @Test
  public final void testSPJ() throws CloneNotSupportedException, PlanningException {
    Expr expr = sqlAnalyzer.parse(QUERIES[6]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    TestLogicalNode.testCloneLogicalNode(plan);
  }

  @Test
  public final void testJson() throws PlanningException {
	  Expr expr = sqlAnalyzer.parse(QUERIES[9]);
	  LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);

	  String json = plan.toJson();
	  LogicalNode fromJson = CoreGsonHelper.fromJson(json, LogicalNode.class);
	  assertEquals(NodeType.ROOT, fromJson.getType());
	  LogicalNode project = ((LogicalRootNode)fromJson).getChild();
	  assertEquals(NodeType.PROJECTION, project.getType());
	  assertEquals(NodeType.HAVING, ((ProjectionNode) project).getChild().getType());
    HavingNode havingNode = ((ProjectionNode) project).getChild();
    assertEquals(NodeType.GROUP_BY, havingNode.getChild().getType());
    GroupbyNode groupbyNode = havingNode.getChild();
    assertEquals(NodeType.SCAN, groupbyNode.getChild().getType());
	  LogicalNode scan = groupbyNode.getChild();
	  assertEquals(NodeType.SCAN, scan.getType());
  }

  @Test
  public final void testVisitor() throws PlanningException {
    // two relations
    Expr expr = sqlAnalyzer.parse(QUERIES[1]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();

    TestVisitor vis = new TestVisitor();
    plan.postOrder(vis);

    assertEquals(NodeType.ROOT, vis.stack.pop().getType());
    assertEquals(NodeType.PROJECTION, vis.stack.pop().getType());
    assertEquals(NodeType.JOIN, vis.stack.pop().getType());
    assertEquals(NodeType.SCAN, vis.stack.pop().getType());
    assertEquals(NodeType.SCAN, vis.stack.pop().getType());
  }

  private static class TestVisitor implements LogicalNodeVisitor {
    Stack<LogicalNode> stack = new Stack<LogicalNode>();
    @Override
    public void visit(LogicalNode node) {
      stack.push(node);
    }
  }


  @Test
  public final void testExprNode() throws PlanningException {
    Expr expr = sqlAnalyzer.parse(QUERIES[10]);
    LogicalPlan rootNode = planner.createPlan(expr);
    LogicalNode plan = rootNode.getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertEquals(NodeType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(NodeType.EXPRS, root.getChild().getType());
    Schema out = root.getOutSchema();

    Iterator<Column> it = out.getColumns().iterator();
    Column col = it.next();
    assertEquals("res1", col.getSimpleName());
    col = it.next();
    assertEquals("res2", col.getSimpleName());
    col = it.next();
    assertEquals("res3", col.getSimpleName());
  }

  @Test
  public final void testAsterisk() throws CloneNotSupportedException, PlanningException {
    Expr expr = sqlAnalyzer.parse(QUERIES[13]);
    LogicalPlan planNode = planner.createPlan(expr);
    LogicalNode plan = planNode.getRootBlock().getRoot();
    assertEquals(NodeType.ROOT, plan.getType());
    TestLogicalNode.testCloneLogicalNode(plan);
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);

    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    ProjectionNode projNode = root.getChild();
    assertEquals(6, projNode.getOutSchema().size());

    assertEquals(NodeType.SELECTION, projNode.getChild().getType());
    SelectionNode selNode = projNode.getChild();

    assertEquals(NodeType.SCAN, selNode.getChild().getType());
    ScanNode scanNode = selNode.getChild();
    assertEquals("employee", scanNode.getTableName());
  }

  static final String ALIAS [] = {
    "select deptName, sum(score) as total from score group by deptName",
    "select em.empId as id, sum(score) as total from employee as em inner join score using (em.deptName) group by id"
  };


  @Test
  public final void testAlias1() throws PlanningException {
    Expr expr = sqlAnalyzer.parse(ALIAS[0]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);

    Schema finalSchema = root.getOutSchema();
    Iterator<Column> it = finalSchema.getColumns().iterator();
    Column col = it.next();
    assertEquals("deptname", col.getSimpleName());
    col = it.next();
    assertEquals("total", col.getSimpleName());

    expr = sqlAnalyzer.parse(ALIAS[1]);
    plan = planner.createPlan(expr).getRootBlock().getRoot();
    root = (LogicalRootNode) plan;

    finalSchema = root.getOutSchema();
    it = finalSchema.getColumns().iterator();
    col = it.next();
    assertEquals("id", col.getSimpleName());
    col = it.next();
    assertEquals("total", col.getSimpleName());
  }

  @Test
  public final void testAlias2() throws PlanningException {
    Expr expr = sqlAnalyzer.parse(ALIAS[1]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);

    Schema finalSchema = root.getOutSchema();
    Iterator<Column> it = finalSchema.getColumns().iterator();
    Column col = it.next();
    assertEquals("id", col.getSimpleName());
    col = it.next();
    assertEquals("total", col.getSimpleName());
  }

  static final String CREATE_TABLE [] = {
    "create external table table1 (name text, age int, earn bigint, score real) using csv with ('csv.delimiter'='|') location '/tmp/data'"
  };

  @Test
  public final void testCreateTableDef() throws PlanningException {
    Expr expr = sqlAnalyzer.parse(CREATE_TABLE[0]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);
    assertEquals(NodeType.CREATE_TABLE, root.getChild().getType());
    CreateTableNode createTable = root.getChild();

    Schema def = createTable.getTableSchema();
    assertEquals("name", def.getColumn(0).getSimpleName());
    assertEquals(Type.TEXT, def.getColumn(0).getDataType().getType());
    assertEquals("age", def.getColumn(1).getSimpleName());
    assertEquals(Type.INT4, def.getColumn(1).getDataType().getType());
    assertEquals("earn", def.getColumn(2).getSimpleName());
    assertEquals(Type.INT8, def.getColumn(2).getDataType().getType());
    assertEquals("score", def.getColumn(3).getSimpleName());
    assertEquals(Type.FLOAT4, def.getColumn(3).getDataType().getType());
    assertEquals(StoreType.CSV, createTable.getStorageType());
    assertEquals("/tmp/data", createTable.getPath().toString());
    assertTrue(createTable.hasOptions());
    assertEquals("|", createTable.getOptions().get("csv.delimiter"));
  }

  private static final List<Set<Column>> testGenerateCuboidsResult
    = Lists.newArrayList();
  private static final int numCubeColumns = 3;
  private static final Column [] testGenerateCuboids = new Column[numCubeColumns];

  private static final List<Set<Column>> testCubeByResult
    = Lists.newArrayList();
  private static final Column [] testCubeByCuboids = new Column[2];
  static {
    testGenerateCuboids[0] = new Column("col1", Type.INT4);
    testGenerateCuboids[1] = new Column("col2", Type.INT8);
    testGenerateCuboids[2] = new Column("col3", Type.FLOAT4);

    testGenerateCuboidsResult.add(new HashSet<Column>());
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[0]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[1]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[2]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[0],
        testGenerateCuboids[1]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[0],
        testGenerateCuboids[2]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[1],
        testGenerateCuboids[2]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[0],
        testGenerateCuboids[1], testGenerateCuboids[2]));

    testCubeByCuboids[0] = new Column("employee.name", Type.TEXT);
    testCubeByCuboids[1] = new Column("employee.empid", Type.INT4);
    testCubeByResult.add(new HashSet<Column>());
    testCubeByResult.add(Sets.newHashSet(testCubeByCuboids[0]));
    testCubeByResult.add(Sets.newHashSet(testCubeByCuboids[1]));
    testCubeByResult.add(Sets.newHashSet(testCubeByCuboids[0],
        testCubeByCuboids[1]));
  }

  @Test
  public final void testGenerateCuboids() {
    Column [] columns = new Column[3];

    columns[0] = new Column("col1", Type.INT4);
    columns[1] = new Column("col2", Type.INT8);
    columns[2] = new Column("col3", Type.FLOAT4);

    List<Column[]> cube = LogicalPlanner.generateCuboids(columns);
    assertEquals(((int)Math.pow(2, numCubeColumns)), cube.size());

    Set<Set<Column>> cuboids = Sets.newHashSet();
    for (Column [] cols : cube) {
      cuboids.add(Sets.newHashSet(cols));
    }

    for (Set<Column> result : testGenerateCuboidsResult) {
      assertTrue(cuboids.contains(result));
    }
  }

  static final String setStatements [] = {
    "select deptName from employee where deptName like 'data%' union select deptName from score where deptName like 'data%'",
  };

  @Test
  public final void testSetPlan() throws PlanningException {
    Expr expr = sqlAnalyzer.parse(setStatements[0]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertEquals(NodeType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(NodeType.UNION, root.getChild().getType());
    UnionNode union = root.getChild();
    assertEquals(NodeType.PROJECTION, union.getLeftChild().getType());
    assertEquals(NodeType.PROJECTION, union.getRightChild().getType());
  }

  static final String [] setQualifiers = {
    "select name, empid from employee",
    "select distinct name, empid from employee",
    "select all name, empid from employee",
  };

  @Test
  public void testSetQualifier() throws PlanningException {
    Expr context = sqlAnalyzer.parse(setQualifiers[0]);
    LogicalNode plan = planner.createPlan(context).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertEquals(NodeType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    ProjectionNode projectionNode = root.getChild();
    assertEquals(NodeType.SCAN, projectionNode.getChild().getType());

    context = sqlAnalyzer.parse(setQualifiers[1]);
    plan = planner.createPlan(context).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertEquals(NodeType.ROOT, plan.getType());
    root = (LogicalRootNode) plan;
    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    projectionNode = root.getChild();
    assertEquals(NodeType.GROUP_BY, projectionNode.getChild().getType());

    context = sqlAnalyzer.parse(setQualifiers[2]);
    plan = planner.createPlan(context).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    root = (LogicalRootNode) plan;
    assertEquals(NodeType.PROJECTION, root.getChild().getType());
    projectionNode = root.getChild();
    assertEquals(NodeType.SCAN, projectionNode.getChild().getType());
  }

  public void testJsonSerDerObject(LogicalNode rootNode) {
    String json = rootNode.toJson();
    LogicalNode fromJson = CoreGsonHelper.fromJson(json, LogicalNode.class);
    assertTrue("JSON (de) serialization equivalence check", rootNode.deepEquals(fromJson));
  }

  // Table descriptions
  //
  // employee (name text, empid int4, deptname text)
  // dept (deptname text, nameger text)
  // score (deptname text, score inet4)

  static final String [] insertStatements = {
      "insert into score select name from employee",                        // 0
      "insert into score select name, empid from employee",                 // 1
      "insert into employee (name, deptname) select * from dept",           // 2
      "insert into location '/tmp/data' select name, empid from employee",  // 3
      "insert overwrite into employee (name, deptname) select * from dept", // 4
      "insert overwrite into LOCATION '/tmp/data' select * from dept"       // 5
  };

  @Test
  public final void testInsertInto0() throws PlanningException {
    Expr expr = sqlAnalyzer.parse(insertStatements[0]);
    LogicalPlan plan = planner.createPlan(expr);
    assertEquals(1, plan.getQueryBlocks().size());
    InsertNode insertNode = getInsertNode(plan);
    assertFalse(insertNode.isOverwrite());
    assertTrue(insertNode.hasTargetTable());
    assertEquals("score", insertNode.getTableName());
  }

  @Test
  public final void testInsertInto1() throws PlanningException {
    Expr expr = sqlAnalyzer.parse(insertStatements[1]);
    LogicalPlan plan = planner.createPlan(expr);
    assertEquals(1, plan.getQueryBlocks().size());
    InsertNode insertNode = getInsertNode(plan);
    assertFalse(insertNode.isOverwrite());
    assertEquals("score", insertNode.getTableName());
  }

  @Test
  public final void testInsertInto2() throws PlanningException {
    Expr expr = sqlAnalyzer.parse(insertStatements[2]);
    LogicalPlan plan = planner.createPlan(expr);
    assertEquals(1, plan.getQueryBlocks().size());
    InsertNode insertNode = getInsertNode(plan);
    assertFalse(insertNode.isOverwrite());
    assertEquals("employee", insertNode.getTableName());
    assertTrue(insertNode.hasTargetSchema());
    assertEquals(insertNode.getTargetSchema().getColumn(0).getSimpleName(), "name");
    assertEquals(insertNode.getTargetSchema().getColumn(1).getSimpleName(), "deptname");
  }

  @Test
  public final void testInsertInto3() throws PlanningException {
    Expr expr = sqlAnalyzer.parse(insertStatements[3]);
    LogicalPlan plan = planner.createPlan(expr);
    assertEquals(1, plan.getQueryBlocks().size());
    InsertNode insertNode = getInsertNode(plan);
    assertFalse(insertNode.isOverwrite());
    assertTrue(insertNode.hasPath());
  }

  @Test
  public final void testInsertInto4() throws PlanningException {
    Expr expr = sqlAnalyzer.parse(insertStatements[4]);
    LogicalPlan plan = planner.createPlan(expr);
    assertEquals(1, plan.getQueryBlocks().size());
    InsertNode insertNode = getInsertNode(plan);
    assertTrue(insertNode.isOverwrite());
    assertTrue(insertNode.hasTargetTable());
    assertEquals("employee", insertNode.getTableName());
    assertTrue(insertNode.hasTargetSchema());
    assertEquals(insertNode.getTargetSchema().getColumn(0).getSimpleName(), "name");
    assertEquals(insertNode.getTargetSchema().getColumn(1).getSimpleName(), "deptname");
  }

  @Test
  public final void testInsertInto5() throws PlanningException {
    Expr expr = sqlAnalyzer.parse(insertStatements[5]);
    LogicalPlan plan = planner.createPlan(expr);
    assertEquals(1, plan.getQueryBlocks().size());
    InsertNode insertNode = getInsertNode(plan);
    assertTrue(insertNode.isOverwrite());
    assertTrue(insertNode.hasPath());
  }

  private static InsertNode getInsertNode(LogicalPlan plan) {
    LogicalRootNode root = plan.getRootBlock().getRoot();
    assertEquals(NodeType.INSERT, root.getChild().getType());
    return root.getChild();
  }
}