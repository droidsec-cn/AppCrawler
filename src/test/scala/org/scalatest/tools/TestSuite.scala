package org.scalatest.tools

import com.xueqiu.qa.appcrawler.CrawlerSuite
import org.scalatest.{Tracker, Filter, Stopper}

/**
  * Created by seveniruby on 16/8/10.
  */
class TestSuite extends CrawlerSuite{
  test("create new suite"){
    //val suite=Eval[FunSuite]("class A extends org.scalatest.FunSuite;new A()")
    val suite=new CrawlerSuite
    suite.registerTest("demo2"){
      println("demo2")
      assert(1==1)
    }
    suite.registerTest("demo3"){
      println("demo3")
      assert(1==2)
    }
    suite.execute
    val htmlReporter=new HtmlReporter("target/reports/", false, None, None)
    val junitXml=new JUnitXmlReporter("target/reports/")
    suite.run(testName = Some("demo3"), reporter = htmlReporter,
      stopper=Stopper.default, tracker = Tracker.default, filter = Filter.default,
      distributor = None, configMap = Map())

  }

  test("demo demo"){
    assert(1==1)
  }

  name="New"
  test("demo demo3"){
    assert(1==1)
  }


  //use last one
  name="NewNew"
  test("demo demo3 demo3"){
    assert(1==1)
  }
}
