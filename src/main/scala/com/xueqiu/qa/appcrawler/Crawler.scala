package com.xueqiu.qa.appcrawler

import java.awt.{Color, BasicStroke}
import java.util.Date
import javax.imageio.ImageIO

import io.appium.java_client.{TouchAction, AppiumDriver}
import org.apache.commons.io.FileUtils
import org.apache.log4j._
import org.openqa.selenium.remote.DesiredCapabilities
import org.openqa.selenium.{OutputType, TakesScreenshot, WebElement}
import org.w3c.dom.Document

import scala.annotation.tailrec
import scala.collection.mutable.{ListBuffer, Map}
import scala.collection.{immutable, mutable}
import scala.reflect.io.File
import scala.util.{Failure, Success, Try}


/**
  * Created by seveniruby on 15/11/28.
  */
class Crawler extends CommonLog {
  implicit var driver: AppiumDriver[WebElement] = _
  var conf = new CrawlerConf()
  val capabilities = new DesiredCapabilities()

  /** 存放插件类 */
  val pluginClasses = ListBuffer[Plugin]()
  var fileAppender: FileAppender = _

  val elements: scala.collection.mutable.Map[UrlElement, Boolean] = scala.collection.mutable.Map()
  private var allElementsRecord = ListBuffer[UrlElement]()
  /** 元素的默认操作 */
  private var currentElementAction = "click"
  /** 点击顺序, 留作画图用 */
  val clickedElementsList = mutable.Stack[UrlElement]()

  protected var automationName = "appium"
  private var screenWidth = 0
  private var screenHeight = 0

  var appName = ""
  var pageSource = ""
  private var pageDom: Document = null
  private var backRetry = 0
  //最大重试次数
  var backMaxRetry = 10
  private var swipeRetry = 0
  //滑动最大重试次数
  var swipeMaxRetry = 2
  private val swipeCountPerUrl = Map[String, Int]()
  private val swipeMaxCountPerUrl = 8
  private var needExit = false
  private val startTime = new Date().getTime

  /** 当前的url路径 */
  var url = ""
  val urlStack = mutable.Stack[String]()

  private val elementTree = TreeNode("AppCrawler")
  private val elementTreeList = ListBuffer[String]()

  protected val backDistance=new DataRecord()
  protected val appNameRecord=new DataRecord()
  protected val contentHash=new DataRecord

  /**
    * 根据类名初始化插件. 插件可以使用java编写. 继承自Plugin即可
    */
  def loadPlugins(): Unit = {
    //todo: 需要考虑默认加载一些插件,并防止重复加载
    /**
    List(
      "com.xueqiu.qa.appcrawler.plugin.ReportPlugin"
    ).foreach(name=>pluginClasses.append(Class.forName(name).newInstance().asInstanceOf[Plugin]))
      */
    conf.pluginList.map(name => {
      log.info(s"load com.xueqiu.qa.appcrawler.plugin $name")
      pluginClasses.append(Class.forName(name).newInstance().asInstanceOf[Plugin])
    })
    val dynamicPluginDir=(new java.io.File(getClass.getProtectionDomain.getCodeSource.getLocation.getPath))
      .getParentFile.getParentFile.getCanonicalPath+File.separator+"plugins"+File.separator
    log.info(s"dynamic load plugin in ${dynamicPluginDir}")
    val dynamicPlugins=Runtimes.loadPlugins(dynamicPluginDir)
    log.info(s"found dynamic plugins size ${dynamicPlugins.size}")
    dynamicPlugins.foreach(pluginClasses.append(_))
    pluginClasses.foreach(log.info)
    pluginClasses.foreach(p => p.init(this))
    pluginClasses.foreach(p => p.start())
  }

  /**
    * 加载配置文件并初始化
    */
  def loadConf(crawlerConf: CrawlerConf): Unit = {
    conf = crawlerConf
    log.setLevel(GA.logLevel)
  }

  def loadConf(file: String): Unit = {
    conf = new CrawlerConf().load(file)
    log.setLevel(GA.logLevel)
  }

  def addLogFile(): Unit = {
    AppCrawler.logPath = conf.resultDir + "/appcrawler.log"

    fileAppender = new FileAppender(layout, AppCrawler.logPath, false)
    log.addAppender(fileAppender)

    val resultDir = new java.io.File(conf.resultDir)
    if (!resultDir.exists()) {
      FileUtils.forceMkdir(resultDir)
      log.info("result dir path = " + resultDir.getAbsolutePath)
    }

  }

  /**
    * 启动爬虫
    */
  def start(existDriver: AppiumDriver[WebElement] = null): Unit = {
    addLogFile()
    loadPlugins()
    GA.log("start")
    if (existDriver == null) {
      log.info("prepare setup Appium")
      setupAppium()
      //driver.getAppStringMap
    } else {
      log.info("use existed driver")
      this.driver = existDriver
    }
    log.info("init MiniAppium")
    log.info(s"platformName=${conf.currentDriver} driver=${driver}")

    log.info("waiting for app load")
    Thread.sleep(8000)
    log.info(s"driver=${existDriver}")
    log.info("get screen info")
    getDeviceInfo()

    MiniAppium.driver=driver
    MiniAppium.screenHeight=screenHeight
    MiniAppium.screenWidth=screenWidth
    MiniAppium.setPlatformName(conf.currentDriver)


    driver.manage().logs().getAvailableLogTypes().toArray.foreach(log.info)
    //设定结果目录

    GA.log("crawler")
    runStartupScript()
    refreshPage()
    crawl()
    //爬虫结束
    clickedElementsList.push(UrlElement(s"${url}-CrawlStop", "", "", "", ""))
    log.info(s"index = ${clickedElementsList.size} current =  ${clickedElementsList.head.loc}")
    refreshPage()
    saveLog()
    saveDom()
    saveScreen(true)
    pluginClasses.foreach(p => p.stop())
  }

  def runStartupScript(): Unit = {
    log.info("run startup script")
    log.info(conf.startupActions)
    conf.startupActions.foreach(action => {
      MiniAppium.dsl(action)
      refreshPage()
      clickedElementsList.push(UrlElement(s"${url}-startupActions-${action}", "", "", "", ""))
      log.info(s"index = ${clickedElementsList.size} current =  ${clickedElementsList.head.loc}")
      saveDom()
      saveScreen(true)
      Thread.sleep(1000)
    })
    swipeRetry = 0
  }

  def setupAppium(): Unit = {
    conf.capability.foreach(kv => capabilities.setCapability(kv._1, kv._2))
    //driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS)
    //PageFactory.initElements(new AppiumFieldDecorator(driver, 10, TimeUnit.SECONDS), this)
    //implicitlyWait(Span(10, Seconds))
  }

  def getDeviceInfo(): Unit = {
    val size = driver.manage().window().getSize
    screenHeight = size.getHeight
    screenWidth = size.getWidth
    log.info(s"screenWidth=${screenWidth} screenHeight=${screenHeight}")
  }


  def black(keys: String*): Unit = {
    keys.foreach(conf.blackList.append(_))
  }

  def md5(format: String) = {
    //import sys.process._
    //s"echo ${format}" #| "md5" !

    //new java.lang.String(MessageDigest.getInstance("MD5").digest(format.getBytes("UTF-8")))
    java.security.MessageDigest.getInstance("MD5").digest(format.getBytes("UTF-8")).map(0xFF & _).map {
      "%02x".format(_)
    }.foldLeft("") {
      _ + _
    }
  }


  def rule(loc: String, action: String, times: Int = 0): Unit = {
    conf.elementActions.append(mutable.Map(
      "idOrName" -> loc,
      "action" -> action,
      "times" -> times))
  }

  /**
    * 根据xpath来获得想要的元素列表
    *
    * @param xpath
    * @return
    */
  def getAllElements(xpath: String): List[immutable.Map[String, Any]] = {
    RichData.getListFromXPath(xpath, pageDom)
  }

  /**
    * 判断内容是否变化
    *
    * @return
    */
  def getContentHash(): String = {
    //var nodeList = getAllElements("//*[not(ancestor-or-self::UIATableView)]")
    //nodeList = nodeList intersect getAllElements("//*[not(ancestor-or-self::android.widget.ListView)]")

    //排除iOS状态栏 android不受影响
    val nodeList = getAllElements("//*[not(ancestor-or-self::UIAStatusBar)]")
    val schemaBlackList = List()
    //加value是考虑到某些tab, 他们只有value不同
    //<             label="" name="分时" path="/0/0/6/0/0" valid="true" value="1"
    //---
    //>             label="" name="分时" path="/0/0/6/0/0" valid="true" value="0"
    md5(nodeList.filter(node => !schemaBlackList.contains(node("tag"))).
      map(node => node.getOrElse("xpath", "") + node.getOrElse("value", "").toString).
      mkString("\n"))
  }

  /**
    * 获得布局Hash
    */
  def getSchema(): String = {
    val nodeList = getAllElements("//*[not(ancestor-or-self::UIAStatusBar)]")
    md5(nodeList.map(getUrlElementByMap(_).toTagPath()).distinct.mkString("\n"))
  }

  def getAppName(): String = {
    return ""
  }


  def getUrl(): String = {
    if (conf.defineUrl.nonEmpty) {
      val urlString = conf.defineUrl.flatMap(getAllElements(_)).distinct.map(x => {
        //按照attribute, label, name顺序挨个取第一个非空的指
        log.info("getUrl")
        log.info(x)
        if(x.contains("attribute")){
          x.getOrElse("attribute", "")
        }else{
          if(x.get("label").nonEmpty){
            x.getOrElse("label", "")
          }else{
            x.getOrElse("name", "")
          }
        }
      }).filter(_.toString.nonEmpty).mkString("-")
      log.info(s"defineUrl=$urlString")
      return urlString
    }
    ""
  }

  /**
    * 获取控件的基本属性并设置一个唯一的uid作为识别. screenName+id+name
    *
    * @param x
    * @return
    */
  def getUrlElementByMap(x: immutable.Map[String, Any]): UrlElement = {
    //控件的类型
    val tag = x.getOrElse("tag", "NoTag").toString

    //name为Android的description/text属性, 或者iOS的value属性
    //appium1.5已经废弃findElementByName
    val name = x.getOrElse("value", "").toString.replace("\n", "\\n").take(30)
    //name为id/name属性. 为空的时候为value属性

    //id表示android的resource-id或者iOS的name属性
    val id = x.getOrElse("name", "").toString.split('/').last
    val loc = x.getOrElse("xpath", "").toString
    UrlElement(url, tag, id, name, loc)

  }

  def isReturn(): Boolean = {
    //超时退出
    if ((new Date().getTime - startTime) > conf.maxTime * 1000) {
      log.warn("maxTime out Quit need exit")
      needExit = true
      return true
    }

    //跳到了其他app
    if (appNameRecord.isDiff()) {
      log.warn(s"jump to other app appName=${appNameRecord.last()} lastAppName=${appNameRecord.pre()}")
      return true
    }
    //url黑名单
    if (conf.urlBlackList.filter(urlStack.head.matches(_)).nonEmpty) {
      log.info(s"${urlStack.head} in urlBlackList should return")
      return true
    }

    //url白名单, 第一次进入了白名单的范围, 就始终在白名单中. 不然就算不在白名单中也得遍历.
    //上层是白名单, 当前不是白名单才需要返回
    if (conf.urlWhiteList.size>0
      && conf.urlWhiteList.filter(urlStack.head.matches(_)).isEmpty
      && conf.urlWhiteList.filter(urlStack.tail.headOption.getOrElse("").matches(_)).nonEmpty) {
      log.info(s"${urlStack.head} not in urlWhiteList should return")
      return true
    }

    //app黑名单
    if (appName.matches(".*browser")) {
      log.info(s"current app is browser, back")
      return true
    }
    //滚动多次没有新元素
    if (swipeRetry > swipeMaxRetry) {
      swipeRetry = 0
      log.info(s"swipe retry too many times ${swipeRetry} > ${swipeMaxRetry}")
      return true
    }
    //超过遍历深度
    log.info(s"urlStack=${urlStack} baseUrl=${conf.baseUrl} maxDepth=${conf.maxDepth}")
    //大于最大深度并且是在进入过基础Url
    if (urlStack.length > conf.maxDepth) {
      log.info(s"urlStack.depth=${urlStack.length} > maxDepth=${conf.maxDepth}")
      return true
    }
    //回到桌面了
    if (urlStack.filter(_.matches("Launcher.*")).nonEmpty || appName.matches("com.android\\..*")) {
      log.warn(s"maybe back to desktop ${urlStack.reverse.mkString("-")} need exit")
      needExit = true
      return true
    }

    false

  }

  /**
    * 黑名单过滤. 通过正则匹配, 判断name和value是否包含黑名单
    *
    * @param uid
    * @return
    */
  def isBlack(uid: immutable.Map[String, Any]): Boolean = {
    conf.blackList.foreach(b => {
      if (List(uid("name"), uid("label"), uid("value")).map(x => {
        x.toString.matches(b)
      }).contains(true)) {
        return true
      }
    })
    false
  }


  def isValid(m: immutable.Map[String, Any]): Boolean = {
    m.getOrElse("visible", "true") == "true" &&
      m.getOrElse("enabled", "true") == "true" &&
      m.getOrElse("valid", "true") == "true"
  }

  //todo: 支持xpath表达式
  def getSelectedElements(): Option[List[immutable.Map[String, Any]]] = {
    var all = List[immutable.Map[String, Any]]()
    var firstElements = List[immutable.Map[String, Any]]()
    var lastElements = List[immutable.Map[String, Any]]()
    var selectedElements = List[immutable.Map[String, Any]]()
    var blackElements = List[immutable.Map[String, Any]]()

    val allElements = getAllElements("//*")
    log.trace(s"all elements = ${allElements.size}")

    conf.blackList.filter(_.head == '/').foreach(xpath => {
      log.trace(s"blackList xpath = ${xpath}")
      val temp=getAllElements(xpath).filter(isValid)
      temp.foreach(log.trace)
      blackElements ++= temp
    })
    conf.selectedList.foreach(xpath => {
      log.trace(s"selectedList xpath =  ${xpath}")
      val temp=getAllElements(xpath).filter(isValid)
      temp.foreach(log.trace)
      selectedElements ++= temp
    })
    selectedElements = selectedElements diff blackElements

    log.trace(conf.firstList)
    conf.firstList.foreach(xpath => {
      log.trace(s"firstList xpath = ${xpath}")
      val temp=getAllElements(xpath).filter(isValid).intersect(selectedElements)
      temp.foreach(log.trace)
      firstElements ++= temp
    })
    log.trace("first elements")
    firstElements.foreach(log.trace)

    conf.lastList.foreach(xpath => {
      log.trace(s"lastList xpath = ${xpath}")
      val temp=getAllElements(xpath).filter(isValid).intersect(selectedElements)
      temp.foreach(log.trace)
      lastElements ++= temp
    })

    //去掉不在first和last中的元素
    selectedElements=selectedElements diff firstElements
    selectedElements=selectedElements diff lastElements

    //确保不重, 并保证顺序
    all = (firstElements ++ selectedElements ++ lastElements).distinct
    log.trace("all elements")
    all.foreach(log.trace)
    log.trace(s"all selected length=${all.length}")
    Some(all)

  }


  def hideKeyBoard(): Unit = {
    //iOS键盘隐藏
    if (getAllElements("//UIAKeyboard").size >= 1) {
      log.info("find keyboard , just hide")
      MiniAppium.retry(driver.hideKeyboard())
    }
  }

  def refreshPage(): Unit = {
    log.info("refresh page")

    if (pageSource.nonEmpty) {
      hideKeyBoard()
    }

    //获取页面结构, 最多重试10次.
    var refreshFinish = false
    pageSource = ""
    1 to 3 foreach (i => {
      if (!refreshFinish) {
        MiniAppium.retry(driver.getPageSource) match {
          case Some(v) => {
            log.trace("get page source success")
            pageSource = v
            pageSource = RichData.toPrettyXML(pageSource)
            Try(RichData.toXML(pageSource)) match {
              case Success(v)=> {
                pageDom=v
                log.trace(pageSource)
                refreshFinish = true
              }
              case Failure(e)=>{
                log.warn("convert to xml fail")
                log.warn(pageSource)
              }
            }
          }
          case None => {
            log.trace("get page source error")
          }
        }
      }
    })
    //todo:appium解析pageSource有bug. 有些页面会始终无法dump. 改成解析不了就后退
    if (!refreshFinish) {
      log.warn("page source get fail, go back")
      goBack()
      return
    }
    val currentUrl = getUrl()
    log.trace(s"url=${currentUrl}")
    //如果跳回到某个页面, 就弹栈到特定的页面, 比如回到首页
    if (urlStack.contains(currentUrl)) {
      while (urlStack.head != currentUrl) {
        urlStack.pop()
      }
    } else {
      urlStack.push(currentUrl)
    }
    //判断新的url堆栈中是否包含baseUrl, 如果有就清空栈记录并从新计数
    if (conf.baseUrl.map(urlStack.head.matches(_)).contains(true)) {
      urlStack.clear()
      urlStack.push(currentUrl)
    }
    log.trace(s"urlStack=${urlStack.reverse}")
    //使用当前的页面. 不再记录堆栈.先简化
    //url = urlStack.reverse.mkString("|")
    url = currentUrl
    log.info(s"url=${url}")

    //val contexts = MiniAppium.doAppium(driver.getContextHandles).getOrElse("")
    //val windows=MiniAppium.doAppium(driver.getWindowHandles).getOrElse("")
    //val windows = ""
    //log.trace(s"windows=${windows}")
    contentHash.append(getContentHash())
    log.info(s"currentContentHash=${contentHash.last()} lastContentHash=${contentHash.pre()}")
    if (contentHash.isDiff()) {
      log.info("ui change")
      saveLog()
    }else{
      log.info("ui not change")
    }
    afterUrlRefresh()

  }

  def afterUrlRefresh(): Unit = {
    pluginClasses.foreach(p => p.afterUrlRefresh(url))
  }

  def isClicked(ele: UrlElement): Boolean = {
    if (elements.contains(ele)) {
      elements(ele)
    } else {
      log.trace(s"element=${ele.toLoc()} first show, need click")
      false
    }
  }


  def beforeElementAction(element: UrlElement): Unit = {
    log.trace("beforeElementAction")
    if(conf.beforeElementAction.nonEmpty){
      log.info("eval beforeElementAction")
      conf.beforeElementAction.foreach(cmd=>{
        log.info(cmd)
        Runtimes.eval(cmd)
      })
    }
    pluginClasses.foreach(p => p.beforeElementAction(element))
  }

  def afterElementAction(element: UrlElement): Unit = {
    log.trace("afterElementAction")
    if (getElementAction() != "skip") {
      refreshPage()
    }

    if(conf.beforeElementAction.nonEmpty){
      log.info("eval afterElementAction")
      conf.beforeElementAction.foreach(cmd=>{
        log.info(cmd)
        Runtimes.eval(cmd)
      })
    }
    pluginClasses.foreach(p => p.afterElementAction(element))
  }

  def getElementAction(): String = {
    currentElementAction
  }

  /**
    * 允许插件重新设定当前控件的行为
    **/
  def setElementAction(action: String): Unit = {
    currentElementAction = action
  }

  def doElementAction(element: UrlElement = clickedElementsList.head): Unit = {
    log.info(s"current element = ${element}")
    log.info(s"current element url = ${element.url}")
    log.info(s"current element xpath = ${element.loc}")
    log.info(s"current element tag path = ${element.toTagPath()}")
    log.info(s"current element file name = ${element.toFileName()}")
    log.info(s"current element uri = ${element.toLoc()}")
    doAppiumAction(element, getElementAction())
  }

  def getBackElements(): ListBuffer[immutable.Map[String, Any]] = {
    conf.backButton.flatMap(getAllElements(_).filter(isValid))
  }

  def goBack(): Unit = {
    log.info("go back")
    //找到可能的关闭按钮, 取第一个可用的关闭按钮
    getBackElements().headOption match {
      case Some(v) if appNameRecord.isDiff()==false => {
        //app相同并且找到back控件才点击. 否则就默认back
        val element = getUrlElementByMap(v)
        clickedElementsList.push(element)
        log.info(s"index = ${clickedElementsList.size} current =  ${clickedElementsList.head.loc}")
        doAppiumAction(element, "click")
        refreshPage()
      }
      case _ => {
        log.warn("find back button error")
        if (conf.currentDriver.toLowerCase == "android") {
          clickedElementsList.push(UrlElement(s"${url}-Back", "", "", "", ""))
          log.info(s"index = ${clickedElementsList.size} current =  ${clickedElementsList.head.loc}")
          saveDom()
          saveScreen(true)
          if(backDistance.intervalMS()<4000){
            log.warn("two back action too close")
            Thread.sleep(4000)
          }
          driver.navigate().back()
          backDistance.append("back")
          refreshPage()
        }else{
          log.warn("you should define you back button in the conf file")
        }
      }
    }


    //超过十次连续不停的回退就认为是需要退出
    backRetry += 1
    log.info("backRetry +=1 refresh page")
    refreshPage()
    if (backRetry > backMaxRetry) {
      log.info(s"backRetry ${backRetry} > backMaxRetry ${backMaxRetry} need exit")
      needExit = true
    } else {
      log.info(s"backRetry=${backRetry}")
    }
  }


  def getAvailableElement(): Seq[UrlElement] = {
    var all = getSelectedElements().getOrElse(List[immutable.Map[String, Any]]())
    log.info(s"all nodes length=${all.length}")
    //去掉黑名单, 这样rule优先级高于黑名单
    all = all.filter(isBlack(_) == false)
    log.info(s"all non-black nodes length=${all.length}")
    //去掉back菜单
    all = all diff getBackElements()
    log.info(s"all non-black non-back nodes length=${all.length}")
    //把元素转换为Element对象
    var allElements = all.map(getUrlElementByMap(_))
    //获得所有未点击元素
    log.info(s"all elements length=${allElements.length}")

    allElements.foreach(allElementsRecord.append(_))
    allElementsRecord = allElementsRecord.distinct
    //过滤已经被点击过的元素
    allElements = allElements.filter(!isClicked(_))
    log.info(s"fresh elements length=${allElements.length}")
    //记录未被点击的元素
    allElements.foreach(e => {
      if (!elements.contains(e)) {
        elements(e) = false
        log.info(s"first found ${e}")
      }
    })

    log.trace("sort all elements")
    allElements.foreach(x => log.trace(x.loc))
    allElements
  }

  /**
    * 优化后的递归方法. 尾递归.
    * 刷新->找元素->点击第一个未被点击的元素->刷新
    */
  @tailrec final def crawl(): Unit = {
    //是否应该退出
    if (needExit) {
      log.warn("need exit")
      return
    }
    //是否需要退出或者后退
    if (isReturn()) {
      log.info("need return")
      goBack()
    }
    //优先匹配规则, 匹配到的时候会自动刷新
    val isHit = doRuleAction()
    //没有匹配到表示可以进行正常的遍历了
    if (isHit == false) {
      val allElements = getAvailableElement()
      if (allElements.nonEmpty) {
        //保存第一个元素到要点击元素的堆栈里, 这里可以决定是后向遍历, 还是前向遍历
        val element = allElements.head
        elements(element) = true
        clickedElementsList.push(element)
        //加载插件分析
        beforeElementAction(element)
        //处理控件
        doElementAction(element)
        //插件处理
        afterElementAction(element)
        //复原
        if(getElementAction()=="skip"){
          clickedElementsList.pop()
        }
        setElementAction("click")

        backRetry = 0
        swipeRetry = 0
      } else {
        log.warn("all elements had be clicked")
        if (conf.needSwipe) {
          swipe()
        }
        goBack()
      }
    }
    crawl()
  }


  def scrollAction(direction: String = "default"): Option[_] = {
    log.info(s"start scroll ${direction}")
    var startX = 0.8
    var startY = 0.8
    var endX = 0.2
    var endY = 0.2
    direction match {
      case "left" => {
        startX = 0.8
        startY = 0.7
        endX = 0.2
        endY = 0.7
      }
      case "right" => {
        startX = 0.2
        startY = 0.5
        endX = 0.8
        endY = 0.5
      }
      case "up" => {
        startX = 0.5
        startY = 0.8
        endX = 0.5
        endY = 0.2
      }
      case "down" => {
        startX = 0.5
        startY = 0.2
        endX = 0.5
        endY = 0.8
      }
      case _ => {
        startX = 0.8
        startY = 0.8
        endX = 0.2
        endY = 0.2
      }
    }

    MiniAppium.retry(
      driver.swipe(
        (screenWidth * startX).toInt, (screenHeight * startY).toInt,
        (screenWidth * endX).toInt, (screenHeight * endY).toInt, 1000
      )
    )


  }

  def tap(x: Int = screenWidth / 2, y: Int = screenHeight / 2): Unit = {
    log.info("tap")
    driver.tap(1, x, y, 100)
    //driver.findElementByXPath("//UIAWindow[@path='/0/2']").click()
    //new TouchAction(driver).tap(x, y).perform()
  }

  def tap(element: WebElement): Unit = {
    driver.tap(1, element, 100)
  }

  def swipe(direction: String = "default"): Unit = {
    if (swipeRetry > swipeMaxRetry) {
      log.info("swipeRetry > swipeMaxRetry")
      return
    }

    if (swipeCountPerUrl.contains(url) == false) {
      swipeCountPerUrl(url) = 0
    }

    if (swipeCountPerUrl(url) > swipeMaxCountPerUrl) {
      log.info("swipeRetry of per url > swipeMaxCountPerUrl")
      swipeRetry += 1
      return
    }

    scrollAction(direction) match {
      case Some(v) => {
        log.trace("swipe success")
        swipeRetry += 1
        swipeCountPerUrl(url) += 1
        log.info(s"swipeCount of current Url=${swipeCountPerUrl(url)}")
        clickedElementsList.push(UrlElement(s"${url}-Scroll${direction}", "", "", "", ""))
        log.info(s"index = ${clickedElementsList.size} current =  ${clickedElementsList.head.loc}")
      }
      case None => {
        log.info("swipe fail")
        goBack()
      }
    }


    /*    MiniAppium.doAppium((new TouchAction(driver))
          .press(screenHeight * 0.5.toInt, screenWidth * 0.5.toInt)
          .moveTo(screenHeight * 0.1.toInt, screenWidth * 0.5.toInt)
          .release()
          .perform()
        )
        MiniAppium.doAppium(driver.executeScript("mobile: scroll", HashMap("direction" -> "up")))*/
    //MiniAppium.doAppium(driver.swipe(screenHeight*0.6.toInt, screenWidth*0.5.toInt, screenHeight*0.1.toInt, screenWidth*0.5.toInt, 400))
  }


  //todo:优化查找方法
  //找到统一的定位方法就在这里定义, 找不到就分别在子类中重载定义
  def findElementByUrlElement(element: UrlElement): Option[WebElement] = {
    //为了加速去掉id定位, 测试表明比xpath竟然还慢
    /*
    log.info(s"find element by uid ${element}")
    if (element.id != "") {
      log.info(s"find by id=${element.id}")
      MiniAppium.doAppium(driver.findElementsById(element.id)) match {
        case Some(v) => {
          val arr = v.toArray().distinct
          if (arr.length == 1) {
            log.trace("find by id success")
            return Some(arr.head.asInstanceOf[WebElement])
          } else {
            //有些公司可能存在重名id
            arr.foreach(log.info)
            log.info(s"find count ${arr.size}, change to find by xpath")
          }
        }
        case None => {
          log.warn("find by id error")
        }
      }
    }
    */
    //todo: 用其他定位方式优化
    log.info(s"find by xpath= ${element.loc}")
    MiniAppium.retry(driver.findElementsByXPath(element.loc)) match {
      case Some(v) => {
        val arr = v.toArray().distinct
        arr.length match {
          case len if len==1 =>{
            log.info("find by xpath success")
            return Some(arr.head.asInstanceOf[WebElement])
          }
          case len if len>1 =>{
            log.warn(s"find count ${v.size()}, you should check your dom file")
            //有些公司可能存在重名id
            arr.foreach(log.info)
            log.warn("just use the first one")
            return Some(arr.head.asInstanceOf[WebElement])
          }
          case len if len==0 => {
            log.warn("find by xpath error no element found")
            refreshPage()
          }
        }

      }
      case None => {
        log.warn("find by xpath error")
      }
    }


    /*
    platformName.toLowerCase() match {
      case "ios" => {
        //照顾iOS android会在findByName的时候自动找text属性.
        MiniAppium.doAppium(driver.findElementByXPath(
          //s"//${uid.tag}[@name='${uid.id}' and @value='${uid.name}' and @x='${uid.loc.split(',').head}' and @y='${uid.loc.split(',').last}']"
          s"//${element.tag}[@path='${element.loc}']"
        )) match {
          case Some(v) => {
            log.trace("find by xpath success")
            return Some(v)
          }
          case None => {
            log.warn("find by xpath error")
          }
        }
      }
      case "android" => {
        //findElementByName在appium1.5中被废弃 https://github.com/appium/appium/issues/6186
        /*
        if (uid.name != "") {
          log.info(s"find by name=${uid.name}")
          MiniAppium.doAppium(driver.findElementsByName(uid.name)) match {
            case Some(v) => {
              val arr=v.toArray().distinct
              if (arr.length == 1) {
                log.info("find by name success")
                return Some(arr.head.asInstanceOf[WebElement])
              } else {
                //有些公司可能存在重名name
                arr.foreach(log.info)
                log.info(s"find count ${arr.size}, change to find by xpath")
              }
            }
            case None => {
            }
          }
        }
        */
        //xpath会较慢
        MiniAppium.doAppium(driver.findElementsByXPath(element.loc)) match {
          case Some(v) => {
            val arr = v.toArray().distinct
            if (arr.length == 1) {
              log.trace("find by xpath success")
              return Some(arr.head.asInstanceOf[WebElement])
            } else {
              //有些公司可能存在重名id
              arr.foreach(log.info)
              log.warn(s"find count ${v.size()}, you should check your dom file")
              if (arr.size > 0) {
                log.info("just use the first one")
                return Some(arr.head.asInstanceOf[WebElement])
              }
            }
          }
          case None => {
            log.warn("find by xpath error")
          }
        }

      }
    }
    */
    None
  }


  def saveLog(): Unit = {
    //记录点击log
    var index = 0
    File(s"${conf.resultDir}/clickedList.log").writeAll(clickedElementsList.reverse.map(n => {
      index += 1
      List(index, n.toFileName, n.toLoc, n.toTagPath).mkString("\n")
    }).mkString("\n"))

    File(s"${conf.resultDir}/clickedList.yml").writeAll(DataObject.toYaml(clickedElementsList))
    File(s"${conf.resultDir}/elementList.yml").writeAll(DataObject.toYaml(elements))
    File(s"${conf.resultDir}/allElements.yml").writeAll(DataObject.toYaml(allElementsRecord))

    File(s"${conf.resultDir}/freemind.mm").writeAll(
      elementTree.generateFreeMind(elementTreeList)
    )
  }

  def getLogFileName(): String = {
    s"${conf.resultDir}/${clickedElementsList.length}_" +
      clickedElementsList.head.toFileName()
  }

  def saveDom(): Unit = {
    //保存dom结构
    val domPath = getLogFileName() + ".dom"
    //感谢QQ:434715737的反馈
    Try(File(domPath).writeAll(pageSource)) match {
      case Success(v)=>{
        log.trace(s"save to ${domPath}")
      }
      case Failure(e)=>{
        log.error(s"save to ${domPath} error")
        log.error(e.getMessage)
        log.error(e.getCause.toString)
        log.error(e.getStackTrace)
      }
    }
  }


  def screenshot(path: String, element: WebElement = null): Unit = {
    if (pluginClasses.map(p => p.screenshot(path)).contains(true)) {
      return
    }
    Try(MiniAppium.shot(element)) match {
      case Success(file) => {
        val imgFile=new java.io.File(path)
        if(imgFile.exists()){
          log.error(s"${path} already exist")
        }else {
          FileUtils.copyFile(file, imgFile)
          log.info("save screenshot end")
        }
      }
      case Failure(e) => {
        log.warn("get screenshot error")

        log.warn(e.getMessage)
        log.warn(e.getCause)
        log.warn(e.getStackTrace.mkString("\n"))
      }
    }


  }


  /**
    * 间隔一定时间判断线程是否完成, 未完成就重试
 *
    * @param interval
    * @param retry
    */
  def retryThread(interval:Int=conf.screenshotTimeout, retry:Int=1)(callback: =>Unit): Unit ={
    var needRetry=true
    1 to retry foreach (i => {
      if (needRetry == true) {
        log.info(s"retry time = ${i}")
        val thread = new Thread(new Runnable {
          override def run(): Unit = {
            callback
          }
        })
        thread.start()
        log.info(s"${thread.getId} ${thread.getState}")

        var stopThreadCount = 0
        var needStopThread = false
        while (needStopThread == false) {
          if (thread.getState != Thread.State.TERMINATED) {
            stopThreadCount += 1
            //超时退出
            if (stopThreadCount >= interval * 2) {
              log.warn("screenshot timeout stop thread")
              thread.stop()
              needStopThread = true
              log.info(thread.getState)
              //refreshPage()
              //发送一个命令测试appium
              //log.info(driver.manage().window().getSize)
            } else {
              //未超时等待
              log.debug("screenshot wait")
              Thread.sleep(500)
            }
          } else {
            //正常退出
            log.trace("screenshot finish")
            needRetry = false
            needStopThread = true
          }
        }
      }

    })

  }
  def saveScreen(force: Boolean = false, element: WebElement = null): Unit = {
    //如果是schema相同. 界面基本不变. 那么就跳过截图加快速度.
    if (conf.saveScreen && contentHash.isDiff() || force) {
      Thread.sleep(100)
      log.info("start screenshot")
      val path = getLogFileName() + ".jpg"
      retryThread(){
        screenshot(path, element)
      }
    }
    else{
      log.info("skip screenshot")
    }
  }


  def appendClickedList(e: UrlElement): Unit = {
    elementTreeList.append(e.url)
    elementTreeList.append(e.loc)
  }

  def doAppiumAction(e: UrlElement, action: String): Option[Unit] = {
    log.info(s"url element=${e} action=${action}")
    if (action == "skip") {
      log.info("action=skip just skip")
      //refreshPage()
      return Some()
    }
    findElementByUrlElement(e) match {
      case Some(v) => {
        log.info(s"find WebElement ${v}")
        log.info(v)
        saveDom()
        saveScreen(false, v)
        action match {
          case "click" => {
            //todo: tap和click的行为不一致. 在ipad上有时候click会点错位置, 而tap不会
            //todo: tap的缺点就是点击元素的时候容易点击到元素上层的控件
            //val res = MiniAppium.doAppium(tap(v))
            val res = MiniAppium.retry(v.click())
            appendClickedList(e)
            if (List("UIATextField", "UIATextView", "EditText").map(e.tag.contains(_)).contains(true)) {
              MiniAppium.retry(driver.hideKeyboard())
            }

          }
          case "skip" => {
            log.info("skip")
          }
          case "scroll left" => {
            swipe("left")
          }
          case "scroll up" => {
            swipe("up")
          }
          case "scroll down" => {
            swipe("down")
          }
          case "tap" => {
            tap()
          }
          case "scroll" => {
            swipe()
          }
          case str: String => {
            log.info(s"sendkeys ${v} with ${str}")
            MiniAppium.retry(v.sendKeys(str)) match {
              case Some(v) => {
                appendClickedList(e)
                MiniAppium.retry(driver.hideKeyboard())
                Some(v)
              }
              case None => None
            }
          }
        }

        Some()
      }
      case None => {
        log.warn("find error")
        None
      }
    }

  }

  /**
    * 子类重载
    *
    * @return
    */
  def getRuleMatchNodes(): List[immutable.Map[String, Any]] = {
    getAllElements("//*").filter(isValid)
  }

  //通过规则实现操作. 不管元素是否被点击过
  def doRuleAction(): Boolean = {
    log.trace("rule match start")
    //先判断是否在期望的界面里. 提升速度
    var isHit = false
    conf.elementActions.foreach(r => {
      log.trace("current rule action")
      log.trace(r)
      val idOrName = r("idOrName").toString
      val action = r("action").toString
      val times = r("times").toString.toInt

      val allMap = if (idOrName.matches("/.*")) {
        //支持xpath
        getAllElements(idOrName)
      } else {
        //支持正则通配
        val all = getRuleMatchNodes()
        (all.filter(_ ("name").toString.matches(idOrName)) ++ all.filter(_ ("value").toString.matches(idOrName))).distinct
      }

      allMap.foreach(x => {
        log.trace(s"rule match size = ${allMap.size} rule = ${r}")
        //获得正式的定位id
        val e = getUrlElementByMap(x)
        log.trace(s"element=${e} action=${action}")
        isHit = true
        clickedElementsList.push(e)
        log.info(s"index = ${clickedElementsList.size} current =  ${clickedElementsList.head.loc}")
        doAppiumAction(e, action.toString) match {
          case None => {
            log.warn("do rule action fail")
          }
          case Some(v) => {
            log.trace("do rule action success")
            r("times") = times - 1
            if (times == 1) {
              log.info(s"remove rule ${r}")
              conf.elementActions -= r
            }
          }
        }

        refreshPage()
        saveLog()
        saveDom()
        saveScreen(true)

      })

    })

    isHit

  }
}
