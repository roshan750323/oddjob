package org.oddjob.designer.components;

import junit.framework.TestCase;

import org.oddjob.Helper;
import org.oddjob.OddjobDescriptorFactory;
import org.oddjob.arooa.ArooaDescriptor;
import org.oddjob.arooa.ArooaParseException;
import org.oddjob.arooa.ArooaType;
import org.oddjob.arooa.design.DesignInstance;
import org.oddjob.arooa.design.DesignParser;
import org.oddjob.arooa.design.view.ViewMainHelper;
import org.oddjob.arooa.standard.StandardArooaSession;
import org.oddjob.arooa.xml.XMLConfiguration;
import org.oddjob.sql.SQLJob;

public class SQLJobDesFaTest extends TestCase {

	DesignInstance design;
	
	public void testCreate() throws ArooaParseException {
		
		String xml =  
			"<sql name='A Test'" +
			"		     id='this'" +
			"            autocommit='true'" +
			"            callable='true'" +
			"            escapeProcessing='true'" +
			"            onError='CONTINUE'" +
			"            delimiter='go'" +
			"            delimiterType='ROW'" +
			"                  >" +
			"   <parameters>" +
			"    <value value='Apple'/>" +
			"    <value value='42'/>" +
			"   </parameters>" +
			"   <input>" +
			"    <buffer>select * from dual</buffer>" +
			"   </input>" +
			"   <results>" +
			"    <sql-results-sheet/>" +
			"   </results>" +
			"</sql>";
		
    	ArooaDescriptor descriptor = 
    		new OddjobDescriptorFactory().createDescriptor(null);
    	
		DesignParser parser = new DesignParser(
				new StandardArooaSession(descriptor));
		parser.setArooaType(ArooaType.COMPONENT);
		
		parser.parse(new XMLConfiguration("TEST", xml));
		
		design = (SqlDesign) parser.getDesign();
		
		assertEquals(SqlDesign.class, design.getClass());
		
		SQLJob test = (SQLJob) Helper.createComponentFromConfiguration(
				design.getArooaContext().getConfigurationNode());
		
		assertEquals(true, test.isCallable());
		assertEquals(true, test.isAutocommit());
		assertEquals(true, test.isEscapeProcessing());
		assertEquals("go", test.getDelimiter());
		assertEquals(SQLJob.DelimiterType.ROW, test.getDelimiterType());
		assertEquals(SQLJob.OnError.CONTINUE, test.getOnError());
		
	}

	public static void main(String args[]) throws ArooaParseException {

		SQLJobDesFaTest test = new SQLJobDesFaTest();
		test.testCreate();
		
		ViewMainHelper view = new ViewMainHelper(test.design);
		view.run();
	}
}
