package org.grails.datastore.gorm.cassandra.transform;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.LocatedMessage;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.grails.compiler.gorm.AdditionalGormEntityTransformation;
import org.codehaus.groovy.syntax.Token;
import org.grails.datastore.mapping.cassandra.utils.EnumUtil;
import org.grails.datastore.mapping.model.MappingFactory;
import org.grails.datastore.mapping.model.config.GormProperties;
import org.grails.datastore.mapping.reflect.AstUtils;
import org.grails.datastore.mapping.reflect.NameUtils;
import org.springframework.cassandra.core.PrimaryKeyType;
import org.springframework.data.annotation.Transient;
import org.springframework.data.cassandra.mapping.CassandraType;
import org.springframework.data.cassandra.mapping.Column;
import org.springframework.data.cassandra.mapping.Indexed;
import org.springframework.data.cassandra.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.mapping.Table;
import org.springframework.util.ClassUtils;

import com.datastax.driver.core.DataType.Name;

/**
 * A AST transformation that turns a GORM entity into a Spring Data Cassandra
 * entity.
 * 
 */
public class GormToCassandraTransform implements AdditionalGormEntityTransformation {

	private static final String MAPPED_WITH = "cassandra";
	private CompilationUnit compilationUnit;

	public void performInjectionOnAnnotatedClass(SourceUnit source, ClassNode classNode) {
		try {
			boolean isHibernateInstalled = ClassUtils.isPresent("org.grails.orm.hibernate.AbstractHibernateDatastore", getClass().getClassLoader());
			boolean cassandraEntity = false;
			String mappedWith = null;
			PropertyNode mappingNode = classNode.getProperty(GormProperties.MAPPING_STRATEGY);
			if (mappingNode != null && mappingNode.isStatic() && mappingNode.getField() != null) {
				Expression expression = mappingNode.getField().getInitialExpression();
				mappedWith = expression.getText() != null ? expression.getText().toLowerCase() : null;
			}
			if (MAPPED_WITH.equals(mappedWith) || !isHibernateInstalled) {
				cassandraEntity = true;
			}
			transformEntity(classNode, cassandraEntity);
		} catch (Exception e) {
			if (source != null) {

                StringWriter output = new StringWriter();
                e.printStackTrace( new PrintWriter(output) );
				String message = "Error occured transfoming GORM entity into Cassandra entity: " +                  output.toString().replaceAll("(\\" + System.getProperty("line.separator") + ")", " ");
				Token token = Token.newString(classNode.getText(), classNode.getLineNumber(), classNode.getColumnNumber());
				LocatedMessage locatedMessage = new LocatedMessage(message, token, source);
				source.getErrorCollector().addFatalError(locatedMessage);
			}
		}
	}

	protected boolean shouldInjectClass(ClassNode classNode) {
		return !isEnum(classNode);
	}

	public static void transformEntity(ClassNode classNode, boolean cassandraEntity) {

		PropertyNode mappingNode = classNode.getProperty(GormProperties.MAPPING);
		Map<String, Map<String, ?>> propertyMappings = new HashMap<String, Map<String, ?>>();
		if (mappingNode != null && mappingNode.isStatic()) {
			populateConfigurationMapFromClosureExpression(classNode, mappingNode, propertyMappings);
		}

		injectVersionPropertyIfNecessary(classNode, propertyMappings);

		// annotate properties listed in the mapping closure
		String primaryKeyPropertyName = GormProperties.IDENTITY;
		for (Entry<String, Map<String, ?>> mappingEntry : propertyMappings.entrySet()) {
			String propertyName = mappingEntry.getKey();
			final Map<String, ?> propertyConfig = mappingEntry.getValue();
			final Map primaryKeyConfig;
			String columnName = null;
			Integer ordinal = null;
			PrimaryKeyType primaryKeyType = PrimaryKeyType.PARTITIONED;
			boolean annotatePrimaryKey = false;
			if (propertyConfig.containsKey("name") && propertyName.equals(GormProperties.IDENTITY)) {
				annotatePrimaryKey = true;
				propertyName = propertyConfig.get("name").toString();
				primaryKeyPropertyName = propertyName;
			}
			if (propertyConfig.containsKey("column")) {
				columnName = (String) propertyConfig.get("column");
			}
			if (propertyConfig.containsKey("primaryKey")) {
				annotatePrimaryKey = true;
				primaryKeyConfig = (Map) propertyConfig.get("primaryKey");
				if (primaryKeyConfig.containsKey("ordinal")) {
					ordinal = StringGroovyMethods.toInteger("" + primaryKeyConfig.get("ordinal"));
				}
				if (primaryKeyConfig.containsKey("type")) {
					primaryKeyType = EnumUtil.findEnum(PrimaryKeyType.class, primaryKeyConfig.get("type").toString().toUpperCase());
				}
			}

			if (propertyConfig.containsKey("type")) {
				String type = propertyConfig.get("type").toString();
				AnnotationNode typeAnnotationNode = null;
				if (("timeuuid").equals(type)) {
					typeAnnotationNode = createTypeAnnotationNode(Name.TIMEUUID);
				} else if (("ascii").equals(type)) {
					typeAnnotationNode = createTypeAnnotationNode(Name.ASCII);
				} else if (("varchar").equals(type)) {
					typeAnnotationNode = createTypeAnnotationNode(Name.VARCHAR);
				} else if (("counter").equals(type)) {
					typeAnnotationNode = createTypeAnnotationNode(Name.COUNTER);
				}
				if (typeAnnotationNode != null) {
					annotateProperty(classNode, propertyName, typeAnnotationNode);
				}
			}

			if (propertyConfig.containsKey("index") && StringGroovyMethods.toBoolean(propertyConfig.get("index").toString())) {
				annotateProperty(classNode, propertyName, Indexed.class);
			}

			if (annotatePrimaryKey) {
				AnnotationNode primaryKeyAnnotation = new AnnotationNode(new ClassNode(PrimaryKeyColumn.class));
				if (columnName != null) {
					primaryKeyAnnotation.addMember("name", new ConstantExpression(columnName));
				}
				if (ordinal != null) {
					primaryKeyAnnotation.addMember("ordinal", new ConstantExpression(ordinal));
				}
				if (primaryKeyType != null) {
					primaryKeyAnnotation.addMember("type", new PropertyExpression(new ClassExpression(new ClassNode(PrimaryKeyType.class)), new ConstantExpression(primaryKeyType)));
				}
				annotateProperty(classNode, propertyName, primaryKeyAnnotation);
			} else if (columnName != null) {
				annotateProperty(classNode, propertyName, createColumnAnnotationNode(columnName));
			}
		}

		injectIdPropertyIfNecessary(classNode, primaryKeyPropertyName, cassandraEntity);

		typeAnnotateProperties(classNode);

		annotateIfNecessary(classNode, Table.class);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void populateConfigurationMapFromClosureExpression(ClassNode classNode, PropertyNode mappingNode, Map propertyMappings) {
		ClosureExpression ce = (ClosureExpression) mappingNode.getInitialExpression();
		final Statement code = ce.getCode();
		if (!(code instanceof BlockStatement)) {
			return;
		}
		final List<Statement> statements = ((BlockStatement) code).getStatements();
		for (Statement statement : statements) {
			if (!(statement instanceof ExpressionStatement)) {
				continue;
			}
			ExpressionStatement es = (ExpressionStatement) statement;
			final Expression expression = es.getExpression();
			if (!(expression instanceof MethodCallExpression)) {
				continue;
			}
			MethodCallExpression mce = (MethodCallExpression) expression;
			final String methodName = mce.getMethodAsString();
			Map propertyMapping = new HashMap();
			propertyMappings.put(methodName, propertyMapping);

			final Expression arguments = mce.getArguments();
			if (arguments instanceof ArgumentListExpression) {
				if (methodName.equals("table")) {
					ArgumentListExpression ale = (ArgumentListExpression) arguments;
					final List<Expression> expressions = ale.getExpressions();
					if (!expressions.isEmpty()) {
						final String tableName = expressions.get(0).getText();
						final AnnotationNode tableAnnotation = new AnnotationNode(new ClassNode(Table.class));
						tableAnnotation.addMember("value", new ConstantExpression(tableName));
						classNode.addAnnotation(tableAnnotation);
					}
				} else if (methodName.equals("version")) {
					ArgumentListExpression ale = (ArgumentListExpression) arguments;
					final List<Expression> expressions = ale.getExpressions();
					if (!expressions.isEmpty()) {
						final Expression expr = expressions.get(0);
						if (expr instanceof ConstantExpression) {
							Object value = ((ConstantExpression) expr).getValue();
							if (value instanceof Boolean) {
								propertyMapping.put("enabled", value);
							} else if (value instanceof String) {
								propertyMapping.put("column", value);
							}
						}
					}
				}
			} else if (arguments instanceof TupleExpression) {
				final List<Expression> tupleExpressions = ((TupleExpression) arguments).getExpressions();
				for (Expression te : tupleExpressions) {
					if (!(te instanceof NamedArgumentListExpression)) {
						continue;
					}

					NamedArgumentListExpression nale = (NamedArgumentListExpression) te;
					for (MapEntryExpression mee : nale.getMapEntryExpressions()) {
						String settingName = mee.getKeyExpression().getText();

						final Expression valueExpression = mee.getValueExpression();
						if (valueExpression instanceof ConstantExpression) {
							if (valueExpression instanceof BooleanExpression) {
								propertyMapping.put(settingName, Boolean.valueOf(valueExpression.getText()));
							} else {
								propertyMapping.put(settingName, valueExpression.getText());
							}
						} else if (valueExpression instanceof MapExpression) {
							final Map<String, String> map = new LinkedHashMap<String, String>();
							propertyMapping.put(settingName, map);
							for (MapEntryExpression mee2 : ((MapExpression) valueExpression).getMapEntryExpressions()) {
								map.put(mee2.getKeyExpression().getText(), mee2.getValueExpression().getText());
							}
						}
					}
				}
			}
		}
	}

	private static void injectIdPropertyIfNecessary(ClassNode classNode, String primaryKeyPropertyName, boolean cassandraEntity) {
		final boolean hasId = AstUtils.hasOrInheritsProperty(classNode, primaryKeyPropertyName);
		ClassNode parent = AstUtils.getFurthestUnresolvedParent(classNode);

		if (!hasId) {
			// inject into furthest relative
			parent.addProperty(primaryKeyPropertyName, Modifier.PUBLIC, new ClassNode(UUID.class), null, null, null);
		}

		PropertyNode primaryKeyProperty = parent.getProperty(primaryKeyPropertyName);
		if (primaryKeyProperty != null) {
			// As a primary key named id, of type long, may have been added by
			// another AST, change its type and getter/setter to UUID for
			// cassandraEntity domain classes
			if (cassandraEntity) {
				ClassNode originalType = primaryKeyProperty.getType();
				String type = originalType.getName();
				if (primaryKeyPropertyName.equals(GormProperties.IDENTITY) && ("long".equals(type) || "java.lang.Long".equals(type))) {
					primaryKeyProperty.setType(new ClassNode(UUID.class));
					MethodNode getter = classNode.getMethod(NameUtils.getGetterName(primaryKeyPropertyName), new Parameter[] {});
					if (getter != null) {
						getter.setReturnType(new ClassNode(UUID.class));
					}
					MethodNode setter = classNode.getMethod(NameUtils.getSetterName(primaryKeyPropertyName), new Parameter[] { new Parameter(originalType, null) });
					if (setter != null) {
						setter.setParameters(new Parameter[] { new Parameter(new ClassNode(UUID.class), setter.getParameters()[0].getName()) });
					}
				}
			}
			// annotate as PrimaryKeyColumn
			AnnotationNode primaryKeyAnnotation = new AnnotationNode(new ClassNode(PrimaryKeyColumn.class));
			primaryKeyAnnotation.addMember("type", new PropertyExpression(new ClassExpression(new ClassNode(PrimaryKeyType.class)), new ConstantExpression(PrimaryKeyType.PARTITIONED)));
			annotateProperty(parent, primaryKeyProperty.getName(), primaryKeyAnnotation);
		}

		// For primary key property not named id, a primary key named id, of
		// type long, may have been added by another AST which should not be
		// persisted. So mark it as Transient, add one now if not present
		if (!primaryKeyPropertyName.equals(GormProperties.IDENTITY)) {
			PropertyNode idProperty = classNode.getProperty(GormProperties.IDENTITY);
			if (idProperty == null) {
				final FieldNode idField = new FieldNode(GormProperties.IDENTITY, Modifier.PRIVATE | Modifier.TRANSIENT, new ClassNode(Long.class), parent.redirect(), new ConstantExpression(null));
				idProperty = new PropertyNode(idField, Modifier.PRIVATE | Modifier.TRANSIENT, null, null);
				parent.addProperty(idProperty);
			}
			if (idProperty != null) {
				String type = idProperty.getType().getName();
				if ("long".equals(type) || "java.lang.Long".equals(type)) {
					idProperty.getField().setModifiers(Modifier.PRIVATE | Modifier.TRANSIENT);
					annotateProperty(classNode, GormProperties.IDENTITY, createTransientAnnotationNode());
				}
			}
		}
	}

	/**
	 * A version property may be added before or after this AST which should not
	 * be persisted by Spring Data Cassandra if version false. So add one now if
	 * not present, so it can be marked as Transient
	 */
	private static void injectVersionPropertyIfNecessary(ClassNode classNode, Map<String, Map<String, ?>> propertyMappings) {
		final boolean hasVersion = AstUtils.hasOrInheritsProperty(classNode, GormProperties.VERSION);
		ClassNode parent = AstUtils.getFurthestUnresolvedParent(classNode);

		if (!hasVersion) {
			// inject into furthest relative
			parent.addProperty(GormProperties.VERSION, Modifier.PUBLIC, new ClassNode(Long.class), null, null, null);
		}

		PropertyNode versionProperty = classNode.getProperty(GormProperties.VERSION);
		if (versionProperty != null) {
			if (propertyMappings.containsKey(GormProperties.VERSION)) {
				final Map<String, ?> versionSettings = propertyMappings.get(GormProperties.VERSION);
				final Object object = versionSettings.get("enabled");
				if (object instanceof Boolean) {
					if (!((Boolean) object).booleanValue()) {
						versionProperty.getField().setModifiers(Modifier.PRIVATE | Modifier.TRANSIENT);
						annotateField(versionProperty.getField(), createTransientAnnotationNode());
					}
				}
			}
		}
	}

	private static void typeAnnotateProperties(ClassNode classNode) {
		final PropertyNode transientsMapping = classNode.getProperty(GormProperties.TRANSIENT);
		List<String> transientPropertyNameList = new ArrayList<String>();
		populateConstantList(transientPropertyNameList, transientsMapping);
		annotateAllProperties(classNode, transientPropertyNameList, createTransientAnnotationNode());

		final List<PropertyNode> properties = classNode.getProperties();
		for (PropertyNode propertyNode : properties) {
			if (!propertyNode.isPublic() || propertyNode.isStatic()) {
				continue;
			}

			final String propertyName = propertyNode.getName();
			if (transientPropertyNameList.contains(propertyName)) {
				continue;
			}
			final String typeName = propertyNode.getType().getName();
			if ((propertyNode.getModifiers() & PropertyNode.ACC_TRANSIENT) != 0) {
				annotateProperty(classNode, propertyName, createTransientAnnotationNode());
			} else if (typeName.equals(UUID.class.getName())) {
				annotateProperty(classNode, propertyName, createTypeAnnotationNode(Name.UUID));
			} else if (typeName.equals(String.class.getName())) {
				annotateProperty(classNode, propertyName, createTypeAnnotationNode(Name.TEXT));
			} else if (typeName.equals(long.class.getName()) || typeName.equals(Long.class.getName())) {
				annotateProperty(classNode, propertyName, createTypeAnnotationNode(Name.BIGINT));
			} else if (typeName.equals(byte.class.getName()) || typeName.equals(Byte.class.getName()) ||
					typeName.equals(short.class.getName()) || typeName.equals(Short.class.getName())) {
				annotateProperty(classNode, propertyName, createTypeAnnotationNode(Name.INT));
			} else if (propertyNode.getType().isEnum()) {
				continue;
			} else if (Object.class.getName().equals(typeName)) {
				annotateProperty(classNode, propertyName, createTransientAnnotationNode());
			} else if (MappingFactory.isSimpleType(typeName)) {
				continue;
			} else if (isCollectionOrMap(propertyNode)) {
				continue;
			} else {
				annotateProperty(classNode, propertyName, createTransientAnnotationNode());
			}
		}
	}

	private static AnnotationNode createTypeAnnotationNode(Name type) {
		AnnotationNode annotationNode = new AnnotationNode(new ClassNode(CassandraType.class));
		// the overridden toString in Name is lowercased which prevents it
		// being found, so need to uppercase it
		annotationNode.addMember("type", new PropertyExpression(new ClassExpression(new ClassNode(Name.class)), new ConstantExpression(type.name().toUpperCase())));
		return annotationNode;
	}

	private static AnnotationNode createColumnAnnotationNode(String columnName) {
		AnnotationNode annotationNode = new AnnotationNode(new ClassNode(Column.class));
		annotationNode.addMember("value", new ConstantExpression(columnName));
		return annotationNode;
	}

	private static AnnotationNode createTransientAnnotationNode() {
		return new AnnotationNode(new ClassNode(Transient.class));
	}

	private static Map<String, String> lookupStringToStringMap(ClassNode classNode, String mapName) {

		final PropertyNode mapProperty = classNode.getProperty(mapName);
		if (mapProperty == null) {
			return Collections.emptyMap();
		}

		final Expression initialExpression = mapProperty.getInitialExpression();
		if (!(initialExpression instanceof MapExpression)) {
			return Collections.emptyMap();
		}

		Map<String, String> stringToClassNodeMap = new HashMap<String, String>();
		MapExpression mapExpr = (MapExpression) initialExpression;
		final List<MapEntryExpression> mapEntryExpressions = mapExpr.getMapEntryExpressions();
		for (MapEntryExpression mapEntryExpression : mapEntryExpressions) {
			final Expression keyExpression = mapEntryExpression.getKeyExpression();
			if (!(keyExpression instanceof ConstantExpression)) {
				continue;
			}
			ConstantExpression ce = (ConstantExpression) keyExpression;
			String propertyName = ce.getValue().toString();
			final Expression valueExpression = mapEntryExpression.getValueExpression();
			if (valueExpression instanceof ConstantExpression) {
				stringToClassNodeMap.put(propertyName, ((ConstantExpression) valueExpression).getValue().toString());
			}
		}
		return stringToClassNodeMap;
	}

	private static Map<String, ClassNode> lookupStringToClassNodeMap(ClassNode classNode, String mapName) {

		if (classNode == null) {
			return Collections.emptyMap();
		}

		final PropertyNode mapProperty = classNode.getProperty(mapName);
		if (mapProperty == null) {
			return Collections.emptyMap();
		}

		final Expression initialExpression = mapProperty.getInitialExpression();
		if (!(initialExpression instanceof MapExpression)) {
			return Collections.emptyMap();
		}

		Map<String, ClassNode> stringToClassNodeMap = new HashMap<String, ClassNode>();
		MapExpression mapExpr = (MapExpression) initialExpression;
		final List<MapEntryExpression> mapEntryExpressions = mapExpr.getMapEntryExpressions();
		for (MapEntryExpression mapEntryExpression : mapEntryExpressions) {
			final Expression keyExpression = mapEntryExpression.getKeyExpression();
			if (!(keyExpression instanceof ConstantExpression)) {
				continue;
			}
			ConstantExpression ce = (ConstantExpression) keyExpression;
			String propertyName = ce.getValue().toString();
			final Expression valueExpression = mapEntryExpression.getValueExpression();
			if (valueExpression instanceof ClassExpression) {
				ClassExpression clsExpr = (ClassExpression) valueExpression;
				stringToClassNodeMap.put(propertyName, clsExpr.getType());
			}
		}
		return stringToClassNodeMap;
	}

	private static void annotateIfNecessary(ClassNode classNode, Class<?> annotationClass) {
		ClassNode annotationClassNode = new ClassNode(annotationClass);

		final List<AnnotationNode> annotations = classNode.getAnnotations(annotationClassNode);
		if (annotations == null || annotations.isEmpty()) {
			classNode.addAnnotation(new AnnotationNode(annotationClassNode));
		}
	}

	private static void annotateAllProperties(ClassNode classNode, List<String> propertyNames, final Class<?> annotation) {
		final AnnotationNode annotationNode = new AnnotationNode(new ClassNode(annotation));
		annotateAllProperties(classNode, propertyNames, annotationNode);
	}

	private static void annotateAllProperties(ClassNode classNode, Collection<String> propertyNames, final AnnotationNode annotationNode) {
		for (String propertyName : propertyNames) {
			annotateProperty(classNode, propertyName, annotationNode);
		}
	}

	private static void annotateProperty(ClassNode classNode, String propertyName, Class<?> annotation) {
		annotateProperty(classNode, propertyName, new AnnotationNode(new ClassNode(annotation)));
	}

	private static void annotateProperty(ClassNode classNode, String propertyName, final AnnotationNode annotationNode) {
		final PropertyNode prop = classNode.getProperty(propertyName);
		if (prop == null) {
			return;
		}
		annotateField(prop.getField(), annotationNode);
	}

	private static void annotateField(final FieldNode fieldNode, final AnnotationNode annotationNode) {
		if (fieldNode == null) {
			return;
		}
		final List<AnnotationNode> annotations = fieldNode.getAnnotations(annotationNode.getClassNode());
		if (annotations == null || annotations.isEmpty()) {
			fieldNode.addAnnotation(annotationNode);
		}
	}

	private static void populateConstantList(List<String> theList, final PropertyNode theProperty) {
		if (theProperty == null) {
			return;
		}

		final Expression initialExpression = theProperty.getInitialExpression();
		if (initialExpression instanceof ListExpression) {
			ListExpression listExpression = (ListExpression) initialExpression;
			final List<Expression> entries = listExpression.getExpressions();
			for (Expression expression : entries) {
				if (expression instanceof ConstantExpression) {
					addConstantExpressionToList(theList, expression);
				}
			}
		} else if (initialExpression instanceof ConstantExpression) {
			addConstantExpressionToList(theList, initialExpression);
		}
	}

	private static void addConstantExpressionToList(List<String> theList, Expression expression) {
		final Object val = ((ConstantExpression) expression).getValue();
		if (val != null) {
			theList.add(val.toString());
		}
	}

	private static boolean isCollectionOrMap(PropertyNode propertyNode) {
		Set<ClassNode> interfaces = propertyNode.getType().getAllInterfaces();
		for (ClassNode inter : interfaces) {
			String name = inter.getName();
			if ("java.util.Collection".equals(name) || "java.util.Map".equals(name)) {
				return true;
			}
		}
		return false;
	}

	private boolean isEnum(ClassNode classNode) {
		ClassNode parent = classNode.getSuperClass();
		while (parent != null) {
			if (parent.getName().equals("java.lang.Enum"))
				return true;
			parent = parent.getSuperClass();
		}
		return false;
	}

	@Override
	public boolean isAvailable() {
		return org.grails.datastore.mapping.reflect.ClassUtils.isPresent("com.datastax.driver.core.DataType");
	}

	@Override
	public void visit(ClassNode classNode, SourceUnit sourceUnit) {
		if(shouldInjectClass(classNode)) {
			performInjectionOnAnnotatedClass(sourceUnit, classNode);
		}
	}

	@Override
	public void setCompilationUnit(CompilationUnit unit) {
		this.compilationUnit = unit;
	}

	@Override
	public void visit(ASTNode[] astNodes, SourceUnit source) {
		AnnotatedNode parent = (AnnotatedNode) astNodes[1];
		ClassNode cNode = (ClassNode) parent;

		visit(cNode, source);
	}
}
