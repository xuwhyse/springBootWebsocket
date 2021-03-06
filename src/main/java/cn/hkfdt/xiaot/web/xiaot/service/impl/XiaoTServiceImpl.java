package cn.hkfdt.xiaot.web.xiaot.service.impl;

import cn.hkfdt.xiaot.mybatis.mapper.ltschina.ForceAnalysisExtendsMapper;
import cn.hkfdt.xiaot.mybatis.mapper.ltschina.SchoolMapper;
import cn.hkfdt.xiaot.mybatis.mapper.ltschina.TQuestionsNewExtendsMapper;
import cn.hkfdt.xiaot.mybatis.mapper.ltschina.TRecordExtendsMapper;
import cn.hkfdt.xiaot.mybatis.model.ltschina.*;
import cn.hkfdt.xiaot.util.ImageUtil;
import cn.hkfdt.xiaot.web.common.globalinit.GlobalInfo;
import cn.hkfdt.xiaot.web.common.redis.RedisClient;
import cn.hkfdt.xiaot.web.common.service.AuthService;
import cn.hkfdt.xiaot.web.xiaot.service.XiaoTService;
import cn.hkfdt.xiaot.web.xiaot.service.md.XiaoTJdbcDriver;
import cn.hkfdt.xiaot.web.xiaot.service.md.XiaoTMDDBHelper;
import cn.hkfdt.xiaot.web.xiaot.service.md.XiaoTMDHelp;
import cn.hkfdt.xiaot.web.xiaot.util.XiaoTMarketType;
import cn.hkfdt.xiaot.web.xiaot.util.YSUtils;
import com.alibaba.fastjson.JSON;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.beanutils.BeanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * author:xumin 
 * 2016-12-15 下午4:12:42
 */
@Service
public class XiaoTServiceImpl implements XiaoTService {

	@Autowired
    JdbcTemplate jdbcTemplate;
	@Autowired
	ForceAnalysisExtendsMapper forceAnalysisExtendsMapper;
	@Autowired
	TQuestionsNewExtendsMapper tQuestionsNewExtendsMapper;
	@Autowired
	TRecordExtendsMapper tRecordExtendsMapper;
	@Autowired
	AuthService authService;
	@Autowired
	SchoolMapper schoolMapper;
	Gson gson = new Gson();
	
	
	AtomicBoolean isInitingTQ = new AtomicBoolean(false);
	private static final int tryNum = 3;//重试次数
	static Logger logger = LoggerFactory.getLogger(XiaoTServiceImpl.class);
	
	//=====================================
	 @PostConstruct
	public void init(){
	 	try {
			XiaoTMDDBHelper.jdbcTemplate = jdbcTemplate;
			XiaoTHelp.init(jdbcTemplate);//初始化一些配置信息
		//	checkTimeOutRecord();
		}catch (Exception e){
	 		e.printStackTrace();
		}
	}
	public static void main(String[] args) {
		// TODO Auto-generated method stub

	}

	@Override
	public ForceAnalysis getXiaotForceAnalysis(String fdtId, int market) {
		ForceAnalysis item = new ForceAnalysis();
		item.setFdtId(fdtId);
		item.setType(market);
		return forceAnalysisExtendsMapper.selectByFdtId(item);
	}

	@Override
	public int getXiaotRecord(Map<String, Object>  mapPara, Map<String, Object> mapTar) {
		List<TRecord> listItem =  tRecordExtendsMapper.getXiaotRecord(mapPara);
		int offset = (int) mapPara.get("offset");
		int count = (int) mapPara.get("count");
//		String fdtId = mapPara.get("fdtId").toString();
//		int market = (int) mapPara.get("market");
		int total = tRecordExtendsMapper.getXiaotRecordTotal(mapPara);
		
		mapTar.put("total", total);
		mapTar.put("offset", offset);
		mapTar.put("count", count);
		mapTar.put("status", 200);
		List<Map<String, Object>>  data = new ArrayList<Map<String,Object>>(listItem.size());
		mapTar.put("data", data);
		for(TRecord item : listItem){
			Map<String, Object> itemTemp = new HashMap<String, Object>(6);
			data.add(itemTemp);
			String symbolCode = item.getSymbol().toUpperCase();//A
			Map<String, Object> symbolItem = XiaoTMDHelp.nM2JYS.get(symbolCode);
			if(symbolItem!=null){
				String rCode = XiaoTMDHelp.getRealCode(symbolCode,symbolItem);
				String chinaName = symbolItem.get("name").toString();
				itemTemp.put("symbolName", chinaName);
				itemTemp.put("symbolCode", rCode);
			}
			itemTemp.put("tradeTime", item.getTradeTime());
			itemTemp.put("returnRate", item.getReturnRate());
			itemTemp.put("volatility", item.getVolatility());
		}
		return 0;
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<Map<String, Object>> getXiaotMasterList(int market,boolean isflush) {
		List<Map<String, Object>> listTar = null;
		String key = "getXiaotMasterList_" + market;
		listTar = (List<Map<String, Object>>) RedisClient.getex(key);
//		boolean isflush = false;
        if (listTar != null && !isflush)
        	return listTar;
		//-------------------------------------------
		List<ForceAnalysis>  listFa = forceAnalysisExtendsMapper.getXiaotMasterList(market);
		if(listFa==null || listFa.isEmpty())
			return null;
		//-------------------------
		List<String> listFdtId = new ArrayList<>(listFa.size());
		for(int i=0;i<listFa.size();i++){
			listFdtId.add(listFa.get(i).getFdtId());
		}
		ConcurrentHashMap<String,Map<String, Object>> fdtId2AuthExInfo = authService.getFdtId2AuthExInfo(listFdtId);
		//-----------------------------
		
		listTar = new ArrayList<Map<String,Object>>(listFa.size());
		for(ForceAnalysis item : listFa){
			try{
				Map<String, Object> mapItem = new HashMap<String, Object>(6);
				
				String fdtId = item.getFdtId();
				float fdtScore = item.getFdtScore();
				float accumulatedIncome = item.getAccumulatedIncome();
				mapItem.put("userId", fdtId);
				mapItem.put("score", fdtScore);
				mapItem.put("returnRate", accumulatedIncome);
				mapItem.put("tradeCount", item.getTradeCount());

				Map<String,Object> mapInfo = fdtId2AuthExInfo.get(fdtId);
				if(mapInfo==null)
					continue;
				if(!mapInfo.containsKey("auth"))
					continue;
				Auth authItem = (Auth) mapInfo.get("auth");
				String school = "";
				School scO = (School) mapInfo.get("school");
				if(scO!=null)
					school = scO.getScName();
				String username = authItem.getUsername();
				String servingUrl = authItem.getServingUrl();
//				servingUrl = DiscoverHelper.operPicURL(servingUrl);
				servingUrl = ImageUtil.transAndResizeImg(servingUrl,100,100);
				boolean isvip = (boolean) mapInfo.get("vip");
				mapItem.put("school", school);
				mapItem.put("userName", username);
				mapItem.put("headimgurl", servingUrl);
				mapItem.put("isvip", isvip);
				
				listTar.add(mapItem);
			}catch(Exception e){
				e.printStackTrace();//防止个别数据出错影响整个排行
			}
		}
		//-------------------------------------
		RedisClient.setex(key, listTar, 60*5);
		return listTar;
	}

	@SuppressWarnings("unchecked")
	@Override
	public TQuestionsNew xiaotTraining(String fdtId, String market, Map<String, Object> mapTar, String type) {
		TQuestionsNew tQuestions = null;
		int count=0;
		while(tQuestions == null){
			TQuestionsNew para = XiaoTHelp.getTQuestion(fdtId,market);
			para.setVersion(GlobalInfo.qversion);
			//获取到题目，而且题目可用
			tQuestions = tQuestionsNewExtendsMapper.getByUnionKey(para);
			++count;
			if(count>=tryNum){
				break;
			}
		}
		byte[] jsonData = tQuestions.getJsonData();
		double v = tQuestions.getVolatility();
		try {
			//获取解压后的真实数据json
			jsonData = YSUtils.uncompress(jsonData);
			Map<String, Object> jsonDataMap = new HashMap<String, Object>(6);
			jsonDataMap = JSON.parseObject(new String(jsonData));//(Map<String, Object>) JsonUtil.JsonToOb(new String(jsonData), jsonDataMap.getClass());
			List<Map<String, Object>> hiList = (List<Map<String,Object>>) (((Map<String,Object>)jsonDataMap.get("history")).get("items"));
			Collections.reverse(hiList);

			Map<String, Object> hMap = (Map<String,Object>)jsonDataMap.get("history");
			List<Map<String, Object>> needHistoryList = new ArrayList<>();
			int index = 0;
			for (Map<String, Object> som : hiList) {
				needHistoryList.add(0, som);
				index++;
				if(index >= 40){
					break;
				}
			}
			hMap.put("items", needHistoryList);
			jsonDataMap.put("history", hMap);
			if(v > 0){//老数据，从表中获取振幅
				Map<String, Object> tMap = (Map<String,Object>)jsonDataMap.get("today");
				tMap.put("volatility", XiaoTHelp.get2Point(v));
			}
			if("history".equalsIgnoreCase(type)){
				jsonDataMap.put("today", null);
			}else if("today".equalsIgnoreCase(type)){
				jsonDataMap.put("history", null);
			}
			mapTar.put("jsonData",jsonDataMap );
			
			mapTar.put("key", XiaoTHelp.getTKey(tQuestions));
			mapTar.put("market", XiaoTHelp.getMarketCode(tQuestions));
			mapTar.put("fdtId", fdtId);
			
			String tradeTime = tQuestions.getTradeDay();
			mapTar.put("tradeTime", tradeTime.replace("-", "."));
			mapTar.put("uniqueId", UUID.randomUUID().toString());
			
		} catch (IOException e) {
			e.printStackTrace();
		}
		return tQuestions;
	}

	@SuppressWarnings("unchecked")
	@Override
	public int xiaotDoScore(final String fdtId, final String body,
							Map<String, Object> mapTar) {

		Map<String, Object> tempMap = new HashMap<String, Object>(1);
		tempMap = JSON.parseObject(body);//(Map<String, Object>) JsonUtil.JsonToOb(body, tempMap.getClass());
		int status = 1;
		String unId = tempMap.get("uniqueId").toString();

		if (tempMap.get("status") != null) {
			status = Integer.parseInt(tempMap.get("status").toString());
		}
		if(status == 3){//超时5分钟到30分钟之间，返回联系结果
			TRecord t = tRecordExtendsMapper.getXiaotRecordByUnid(unId);
			mapTar = JSON.parseObject(t.getScoreResult());
			return 0;
		}
		String[] strs = tempMap.get("key").toString().split("#");
//获取题目
		TQuestionsNew para = new TQuestionsNew();
		para.setExchangeCode(strs[0]);
		para.setShortSymbol(strs[1]);
		para.setTradeDay(strs[2]);
		para.setVersion(GlobalInfo.qversion);
		TQuestionsNew tq = tQuestionsNewExtendsMapper.getByUnionKey(para);

		tempMap.put("exchangeCode", strs[0]);
		tempMap.put("symbol", strs[1]);
		tempMap.put("fdtId", fdtId);
		if (tempMap.containsKey("version")) {
			XiaoTHelp.version = (int) tempMap.get("version");
		}
		mapTar.put("status", 200);
		if (!fdtId.equals(XiaoTHelp.xiaoTGuest) && status == 0) {

			//---------登录用户才可以记录战绩---------------------
			TRecord record = new TRecord();
			try {
				BeanUtils.copyProperties(record, tempMap);//
			} catch (Exception e) {
				e.printStackTrace();
			}
			//判断action的最后一个点是否是平仓，如果不是，添加用最后一根线的价格平仓的数据
			String actionJson = tempMap.get("actions").toString();
			List<TraningAction> acList = gson.fromJson(actionJson, new TypeToken<List<TraningAction>>(){}.getType());
			TraningAction lastAction = acList.get(acList.size() - 1);
			if(!lastAction.getSide().equals("close")){
				TraningAction closeAction = new TraningAction();
				closeAction.setSide("close");

				closeAction.setTimestamp(System.currentTimeMillis());

				byte[] jsonData = tq.getJsonData();
				try {
					//获取解压后的真实数据json
					jsonData = YSUtils.uncompress(jsonData);
					Map<String, Object> jsonDataMap = new HashMap<String, Object>(6);
					jsonDataMap = JSON.parseObject(new String(jsonData));//(Map<String, Object>) JsonUtil.JsonToOb(new String(jsonData), jsonDataMap.getClass());
					Map<String, Object> todayMap = (Map<String, Object>) jsonDataMap.get("today");
					List<Map<String, Object>> infoList = (List<Map<String, Object>>)todayMap.get("items");
					Map<String, Object> lastInfo = infoList.get(infoList.size() - 1);
					closeAction.setPrice(Integer.parseInt(lastInfo.get("closePrice").toString()));
					closeAction.setCurIdx(infoList.size()-1);
					acList.add(closeAction);
				} catch (IOException e) {
					e.printStackTrace();

				}
			}
			record.setActions(gson.toJson(acList));
			record.setQuestionKey(tempMap.get("key").toString());
			record.setCreateTime(System.currentTimeMillis());
			record.setScore(0d);
			record.setUniqueId(unId);
			record.setVolatility(0f);
			record.setVERSION(XiaoTHelp.version);//新数据需要加上
			record.setStatus(status);
			record.setReqBody(JSON.toJSONString(tempMap));
			if (record.getType() == null) {
				record.setType(0);
			}
			tRecordExtendsMapper.replaceXiaotRecord(record);
		}
		//当status为1时请求战力打分
		int count = 0;
		if (status == 1) {
			while (count <= tryNum) {
				Map<String, Object> temp = XiaoTHelp.xiaotDoScore(JSON.toJSONString(tempMap));
				if (temp != null && !temp.isEmpty()) {
					int code = (int) temp.get("code");
					if (code == 200) {
						double score = 0.0;
						if (temp.get("score") != null) {
							score = Double.parseDouble(temp.get("score").toString());
						}

						boolean win = (boolean) temp.get("win");
						setXiaotDoScoreRtnMap(mapTar, score, tempMap, win, tq.getCnName());
						if (fdtId.equals(XiaoTHelp.xiaoTGuest))
							return 0;

						TRecord record = new TRecord();
						try {
							BeanUtils.copyProperties(record, tempMap);//
						} catch (Exception e) {
							e.printStackTrace();
						}
						record.setActions(tempMap.get("actions").toString());
						record.setQuestionKey(tempMap.get("key").toString());
						record.setCreateTime(System.currentTimeMillis());
						record.setScore(score);
						record.setUniqueId(unId);
						record.setVolatility(Float.parseFloat(tempMap.get("volatility").toString()));
						record.setVERSION(XiaoTHelp.version);//新数据需要加上
						record.setStatus(status);
						record.setReqBody(JSON.toJSONString(tempMap));
						if (record.getType() == null) {
							record.setType(0);
						}
						tRecordExtendsMapper.replaceXiaotRecord(record);

						//-----请求战力分析，并且更新数据库-----------
						Runnable run = new Runnable() {

							@Override
							public void run() {
								try {
									Thread.sleep(500);
								} catch (InterruptedException e) {
									e.printStackTrace();
								}
								setxiaoTZL(fdtId, body);
							}
						};
						XiaoTHelp.executorService.execute(run);
						//----------------------------------------------

						return 0;
					}
				}
				++count;
				if (count >= tryNum) {
					if (temp != null && !temp.isEmpty()) {
						String msg = temp.get("msg").toString();
						mapTar.put("msg", msg);
						return -2;
					}
					return -1;
				}
			}
		}
		return 0;
	}
	//设置打分后的返回数据
	private void setXiaotDoScoreRtnMap(Map<String, Object> mapTar,
			double score, Map<String, Object> tempMap, boolean win, String cnName) {
		String symbol = tempMap.get("symbol").toString();
		String chinaName = cnName;
		if(chinaName == null || "".equals(chinaName)) {
			Map<String, Object> symbolItem = XiaoTMDHelp.nM2JYS.get(symbol);
			chinaName = symbolItem.get("name").toString();
		}
		String tradeTime = tempMap.get("tradeTime").toString();
		
		String returnRate = tempMap.get("returnRate").toString();
		String volatility = tempMap.get("volatility").toString();
		
		mapTar.put("score",XiaoTHelp.get2Point(score));
		mapTar.put("tradeTime", tradeTime);
		mapTar.put("symbolName", chinaName);
		mapTar.put("returnRate", returnRate);
		mapTar.put("volatility", volatility);
		mapTar.put("win", win);
		
	}

	/**
	 * 每次打分成功后，请求服务，更新战力分析数据到本数据库
	 * @param fdtId
	 * @param body
	 * author:xumin 
	 * 2016-12-19 下午4:07:39
	 */
	private void setxiaoTZL(String fdtId, String body) {
		int count=0;
		while(count<=tryNum){
			Map<String, Object> temp = XiaoTHelp.getXiaoTZL(fdtId,body);
			if(temp!=null && !temp.isEmpty()){
				int code = (int) temp.get("code");
				if(code==200){
					double fdtScore = Double.parseDouble(temp.get("score").toString());
					double accumulatedIncome =  Double.parseDouble(temp.get("accumulatedIncome").toString());
					int tradeCount = (int) temp.get("tradeCount");
					int winCount = (int) temp.get("winCount");
					double maxDrawDown = Double.parseDouble(temp.get("maxDrawDown").toString());
					double sharpeRatio = Double.parseDouble(temp.get("sharpeRatio").toString());
					
					ForceAnalysis record = new ForceAnalysis();
					record.setFdtScore(Float.parseFloat(XiaoTHelp.get2Point(fdtScore)));
					record.setAccumulatedIncome(Float.parseFloat(XiaoTHelp.get2Point(accumulatedIncome)));
					record.setFdtId(fdtId);
					record.setMaxDrawdown(Float.parseFloat(XiaoTHelp.get2Point(maxDrawDown)));
					record.setSharpeRatio(Float.parseFloat(XiaoTHelp.get2Point(sharpeRatio)));
					record.setTradeCount(tradeCount);
					record.setType(XiaoTMarketType.FC.getType());
					record.setWinCount(winCount);
					record.setCreateTime(System.currentTimeMillis());

					if(temp.containsKey("version")){
						int version = (int) temp.get("version");
						record.setVERSION(version);
					}

					ForceAnalysis tempAna = forceAnalysisExtendsMapper.selectByFdtId(record);
					if(tempAna==null)
						forceAnalysisExtendsMapper.insertSelective(record);
					else{
						record.setForceId(tempAna.getForceId());
						forceAnalysisExtendsMapper.updateByPrimaryKeySelective(record);
					}
					return;
				}
			}
			++count;
		}
	}

	
	//================================================================================
	@Override
	public int initTQuestions(final int market, Map<String, Object> mapTar) {
		if(isInitingTQ.get())
			return -1;//正在进行初始化数据
		isInitingTQ.set(true);
		Runnable run = new Runnable() {
			
			@Override
			public void run() {
				try{
					List<TQuestionsNew> listQ = tQuestionsNewExtendsMapper.initTQuestions(market);
					for(TQuestionsNew item : listQ){
						List<Map<String, Object>> listMapday = XiaoTMDDBHelper.getHistoryDayData(item,market);
						if(listMapday!=null && listMapday.size()==79){
							List<Map<String, Object>> listMapMin = XiaoTMDDBHelper.getHistoryMinData(item, market);
							if(listMapMin!=null && listMapMin.size()>=200){
								Map<String, Object>  mapTar = new HashMap<String, Object>(2);
								int flag = XiaoTMDHelp.getJsonData(listMapday,item,market,listMapMin,mapTar);
								if(flag==0){
									String jsonData = JSON.toJSONString(mapTar);//JsonUtil.ObToJson(mapTar);
									try {
										byte[] byteTar = YSUtils.compress(jsonData.getBytes(), 2);
										item.setJsonData(byteTar);
										item.setInitType(1);
									} catch (IOException e) {
										item.setInitType(-3);//为止原因之一
										e.printStackTrace();
									}
								}else{
									item.setInitType(-4);//计算失败
								}
							}else{
								//插入数据失败的标志
								item.setInitType(-2);//分钟线少于200
							}
						}else{
							//插入数据失败的标志
							item.setInitType(-1);//日行情少于79天
						}
						tQuestionsNewExtendsMapper.updateByPrimaryKeySelective(item);
					}
				}finally{
					XiaoTJdbcDriver.closeCon();
					isInitingTQ.set(false);
				}
			}
		};
		Thread td = new Thread(run);
		td.setName("初始化小T行情数据");
		td.start();
		return 0;
	}


	/**
	 * 每两分钟检查一次超时5分钟以上的交易，请求打分
	 */
	@Scheduled(cron = "0 0/2 * * * ?")
	public void checkTimeOutRecord(){
		Calendar beforeTime = Calendar.getInstance();
		beforeTime.add(Calendar.MINUTE, -5);// 5分钟之前的时间
		long time =beforeTime.getTimeInMillis();
		List<TRecord> recordList = tRecordExtendsMapper.getTimeOutRecord(time);
		if(recordList != null && recordList.size() > 0){
			for (final TRecord tRecord : recordList) {
				if (tRecord.getFdtId().equals(XiaoTHelp.xiaoTGuest)) {
					return;
				}
				Map<String, Object> mapTar = new HashMap<>();
				mapTar.put("status", 200);
				int count = 0;
				while (count <= tryNum) {
					Map<String, Object> tempMap = new HashMap<String, Object>(1);
					tempMap = JSON.parseObject(tRecord.getReqBody());

					String[] strs = tempMap.get("key").toString().split("#");
					tempMap.put("exchangeCode", strs[0]);
					tempMap.put("symbol", strs[1]);
					tempMap.put("fdtId", tRecord.getFdtId());
					//获取题目
					TQuestionsNew tpara = new TQuestionsNew();
					tpara.setExchangeCode(strs[0]);
					tpara.setShortSymbol(strs[1]);
					tpara.setTradeDay(strs[2]);
                    tpara.setVersion(GlobalInfo.qversion);
					TQuestionsNew tq = tQuestionsNewExtendsMapper.getByUnionKey(tpara);
					Map<String, Object> temp = XiaoTHelp.xiaotDoScore(tRecord.getReqBody());
					if (temp != null && !temp.isEmpty()) {
						int code = (int) temp.get("code");
						if (code == 200) {
							double score = 0.0;

							float volatility = 0f;
							if (temp.get("score") != null) {
								score = Double.parseDouble(temp.get("score").toString());
							}

							boolean win = (boolean) temp.get("win");
							setXiaotDoScoreRtnMap(mapTar, score, tempMap, win, tq.getCnName());
							//更新score
							Map<String, Object> para = new HashMap<>();
							para.put("score", score);
							para.put("id", tRecord.getRecordId());
							para.put("volatility", Float.parseFloat(tempMap.get("volatility").toString()));
							para.put("createTime", System.currentTimeMillis());
							para.put("scoreResult", gson.toJson(mapTar));
							tRecordExtendsMapper.updateScore(para);


							//-----请求战力分析，并且更新数据库-----------
							Runnable run = new Runnable() {

								@Override
								public void run() {
									try {
										Thread.sleep(500);
									} catch (InterruptedException e) {
										e.printStackTrace();
									}
									setxiaoTZL(tRecord.getFdtId(), tRecord.getReqBody());
								}
							};
							XiaoTHelp.executorService.execute(run);
							//----------------------------------------------


						}
					}
					count++;

				}
			}
		}

	}

	class TraningAction implements Serializable {
		private String side;
		private int price;
		private int curIdx;
		private long timestamp;

		public String getSide() {
			return side;
		}

		public void setSide(String side) {
			this.side = side;
		}

		public int getPrice() {
			return price;
		}

		public void setPrice(int price) {
			this.price = price;
		}

		public int getCurIdx() {
			return curIdx;
		}

		public void setCurIdx(int curIdx) {
			this.curIdx = curIdx;
		}

		public long getTimestamp() {
			return timestamp;
		}

		public void setTimestamp(long timestamp) {
			this.timestamp = timestamp;
		}
	}
}
