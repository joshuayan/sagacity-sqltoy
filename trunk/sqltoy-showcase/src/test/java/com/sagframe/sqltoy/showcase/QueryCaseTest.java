/**
 * 
 */
package com.sagframe.sqltoy.showcase;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.annotation.Resource;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagacity.sqltoy.callback.DataSourceCallbackHandler;
import org.sagacity.sqltoy.dao.SqlToyLazyDao;
import org.sagacity.sqltoy.model.TreeTableModel;
import org.sagacity.sqltoy.service.SqlToyCRUDService;
import org.sagacity.sqltoy.utils.DataSourceUtils;
import org.sagacity.sqltoy.utils.DateUtil;
import org.sagacity.sqltoy.utils.SqlUtil;
import org.sagacity.sqltoy.utils.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import com.alibaba.fastjson.JSON;
import com.sagframe.sqltoy.SqlToyApplication;
import com.sagframe.sqltoy.showcase.vo.DeviceOrderInfoVO;
import com.sagframe.sqltoy.showcase.vo.DictDetailVO;
import com.sagframe.sqltoy.showcase.vo.OrganInfoVO;
import com.sagframe.sqltoy.showcase.vo.StaffInfoVO;
import com.sagframe.sqltoy.utils.ShowCaseUtils;

/**
 * @project sqltoy-boot-showcase
 * @description 演示sql查询
 * @author chenrenfei <a href="mailto:zhongxuchen@gmail.com">联系作者</a>
 * @version id:QueryCaseTest.java,Revision:v1.0,Date:2019年7月15日
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = SqlToyApplication.class)
public class QueryCaseTest {
	@Resource(name = "sqlToyLazyDao")
	private SqlToyLazyDao sqlToyLazyDao;

	@Autowired
	private SqlToyCRUDService sqlToyCRUDService;

	/**
	 * 初次先执行此方法, 加载数据字典、订单等模拟数据sql文件
	 */
	// @Before
	@Test
	public void initData() {
		// 加载初始化数据脚本(最好手工执行数据初始化,便于演示缓存翻译功能)
		final String sqlContent = ShowCaseUtils.loadFile("classpath:/mock/initDataSql.sql", "UTF-8");
		if (StringUtil.isBlank(sqlContent))
			return;
		DataSourceUtils.processDataSource(sqlToyLazyDao.getSqlToyContext(), sqlToyLazyDao.getDataSource(),
				new DataSourceCallbackHandler() {
					@Override
					public void doConnection(Connection conn, Integer dbType, String dialect) throws Exception {
						// executeBatchSql可以根据数据库类型将大的sql字符进行分割循环执行
						SqlUtil.executeBatchSql(conn, sqlContent, 100, true);
					}
				});

		// 模拟订单信息
		List<DeviceOrderInfoVO> orderInfos = new ArrayList<DeviceOrderInfoVO>();
		int max = 1000;
		// 查询全部员工
		List<StaffInfoVO> staffs = sqlToyLazyDao.findBySql("select STAFF_ID,STAFF_NAME,ORGAN_ID from sqltoy_staff_info",
				null, null, StaffInfoVO.class);
		StaffInfoVO staff;
		int[] days = { 10, 15, 20, 30, 60 };
		Date nowTime = DateUtil.getNowTime();
		List<DictDetailVO> deviceTypes = sqlToyLazyDao.findBySql(
				"select * from sqltoy_dict_detail where dict_type=:dictType", new DictDetailVO(null, "DEVICE_TYPE"));
		// 采购、销售标志
		String[] psTypes = { "PO", "SO" };
		for (int i = 0; i < max; i++) {
			DeviceOrderInfoVO orderVO = new DeviceOrderInfoVO();
			staff = staffs.get(ShowCaseUtils.getRandomNum(staffs.size() - 1));
			orderVO.setBuyer("C000" + i);
			orderVO.setSaler("S000" + i);
			orderVO.setStaffId(staff.getStaffId());
			orderVO.setOrganId(staff.getOrganId());
			orderVO.setTransDate(nowTime);
			orderVO.setDeliveryTerm(DateUtil.addDay(nowTime, days[ShowCaseUtils.getRandomNum(4)]));
			orderVO.setDeviceType(deviceTypes.get(ShowCaseUtils.getRandomNum(deviceTypes.size() - 1)).getDictKey());
			orderVO.setPsType(psTypes[ShowCaseUtils.getRandomNum(1)]);
			orderVO.setTotalCnt(new BigDecimal(ShowCaseUtils.getRandomNum(100, 400)));
			orderVO.setTotalAmt(orderVO.getTotalCnt().multiply(BigDecimal.valueOf(500)));
			orderVO.setStatus(1);
			orderVO.setCreateBy("S0001");
			orderVO.setUpdateBy("S0001");
			orderInfos.add(orderVO);
		}
		// 事务控制在service层上面的
		sqlToyCRUDService.saveAll(orderInfos);
	}

	/**
	 * 对树形表(类似于机构、产品分类等)组织:节点路径、节点等级、是否叶子节点 通用字段值,便于利用通用的sql实现递归查询(数据库无关)
	 */
	@Test
	public void wrapTreeTable() {
		// 根机构
		OrganInfoVO organVO = sqlToyLazyDao.load(new OrganInfoVO("100001"));
		TreeTableModel treeTableModel = new TreeTableModel(organVO);
		// 设置父节点
		treeTableModel.pidField("organPid");

		// 节点路径、节点等级、是否叶子节点，可以不用设置(默认值是nodeRoute、nodeLevel、isLeaf)
		treeTableModel.nodeLevelField("nodeLevel");
		treeTableModel.nodeRouteField("nodeRoute");
		treeTableModel.isLeafField("isLeaf");

		// 构造节点路径
		sqlToyLazyDao.wrapTreeTableRoute(treeTableModel);
	}

	/**
	 * 树形结构节点递归查询 1、通过节点路径nodeRoute查询下级子机构 2、通过节点等级控制查询多少级 3、通过是否叶子节点控制查询最底层还是非最底层
	 */
	@Test
	public void treeTableSearch() {
		// select * from sqltoy_organ_info t
		// where exists
		// (
		// select 1 from sqltoy_organ_info t1
		// where t1.`ORGAN_ID`=t.`ORGAN_ID`
		// -- 通过节点路径包含关系
		// and instr(t1.`NODE_ROUTE`,:nodeRoute)
		// -- 排除自身 and t.ORGAN_ID<>:organId
		// -- and t.NODE_LEVEL<=:nodeLevel+2
		// -- and t.IS_LEAF=0
		// )
		// 父节点机构，查询其下层所有节点,也可以用节点等级 和是否叶子节点来控制提取第几层
		OrganInfoVO parentOrgan = sqlToyLazyDao.load(new OrganInfoVO("100008"));
		List<OrganInfoVO> subOrgans = sqlToyLazyDao.findBySql("sqltoy_treeTable_search", parentOrgan);
		System.out.print(JSON.toJSONString(subOrgans));
	}

	/**
	 * 根据对象加载数据
	 */
	@Test
	public void loadByEntity() {
		OrganInfoVO parentOrgan = sqlToyLazyDao.load(new OrganInfoVO("100008"));
		System.out.print(JSON.toJSONString(parentOrgan));
	}

	/**
	 * 普通sql加载对象
	 */
	@Test
	public void loadBySql() {
		List<OrganInfoVO> subOrgans = sqlToyLazyDao.findBySql("sqltoy_treeTable_search", new String[] { "nodeRoute" },
				new Object[] { ",100008," }, OrganInfoVO.class);
		System.out.print(JSON.toJSONString(subOrgans));
	}

	/**
	 * 唯一性验证
	 * 返回false表示已经存在;返回true表示唯一可以插入
	 */
	@Test
	public void unique() {
		DictDetailVO dictDetail = new DictDetailVO("PC", "DEVICE_TYPE");
		//第一个参数，放入需要验证的对象
		//第二个参数，哪几个字段值进行唯一性检查
		boolean isExist = sqlToyLazyDao.isUnique(dictDetail, new String[] { "dictKey", "dictType" });
		//unique 返回false表示已经存在;返回true表示唯一可以插入
		//在记录变更时,带入主键值，会自动判断是否是自身
		System.err.println(isExist);
	}

	/**
	 * 普通sql查询
	 */
	@Test
	public void findBySql() {
		List result = (List) sqlToyLazyDao.findBySql("biz_test", null);
		System.err.println(JSON.toJSONString(result));
	}

	/**
	 * 分页查询
	 */
	@Test
	public void findPage() {

	}

	/**
	 * 取前多少条记录
	 */
	@Test
	public void findTop() {

	}

	/**
	 * 查询随机记录
	 */
	@Test
	public void findByRandom() {

	}
}
