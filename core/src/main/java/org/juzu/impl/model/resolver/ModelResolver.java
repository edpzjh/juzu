/*
 * Copyright (C) 2011 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.juzu.impl.model.resolver;

import org.juzu.impl.template.compiler.Template;
import org.juzu.impl.utils.JSON;
import org.juzu.metadata.Cardinality;
import org.juzu.request.Phase;
import org.juzu.Response;
import org.juzu.URLBuilder;
import org.juzu.impl.application.InternalApplicationContext;
import org.juzu.impl.compiler.BaseProcessor;
import org.juzu.impl.compiler.CompilationException;
import org.juzu.impl.compiler.ElementHandle;
import org.juzu.impl.model.CompilationErrorCode;
import org.juzu.impl.model.meta.ApplicationMetaModel;
import org.juzu.impl.model.meta.ControllerMetaModel;
import org.juzu.impl.model.meta.MetaModel;
import org.juzu.impl.model.meta.MetaModelEvent;
import org.juzu.impl.model.meta.MetaModelObject;
import org.juzu.impl.model.meta.MethodMetaModel;
import org.juzu.impl.model.meta.TemplateMetaModel;
import org.juzu.impl.model.meta.TemplateRefMetaModel;
import org.juzu.impl.model.processor.ModelHandler;
import org.juzu.impl.model.processor.ProcessingContext;
import org.juzu.impl.spi.template.TemplateProvider;
import org.juzu.impl.utils.FQN;
import org.juzu.impl.utils.Logger;
import org.juzu.impl.utils.Tools;
import org.juzu.metadata.ApplicationDescriptor;
import org.juzu.metadata.ControllerDescriptor;
import org.juzu.metadata.ControllerMethod;
import org.juzu.metadata.ControllerParameter;
import org.juzu.request.ActionContext;
import org.juzu.request.MimeContext;

import javax.annotation.Generated;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.VariableElement;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** @author <a href="mailto:julien.viet@exoplatform.com">Julien Viet</a> */
public class ModelResolver extends ModelHandler implements Serializable
{

   /** . */
   private static final Logger log = BaseProcessor.getLogger(ModelResolver.class);

   /** . */
   public static final Pattern TEMPLATE_PATH_PATTERN = Pattern.compile("([^/].*/|)([^./]+)\\.([a-zA-Z]+)");

   /** . */
   private static final Pattern PROVIDER_PKG_PATTERN = Pattern.compile(
      "org\\.juzu\\.impl\\.spi\\.template\\.([^.]+)(?:\\..+)?"
   );

   /** . */
   private static final String APPLICATION_DESCRIPTOR = ApplicationDescriptor.class.getSimpleName();

   /** . */
   private static final String CONTROLLER_METHOD = ControllerMethod.class.getSimpleName();

   /** . */
   private static final String CONTROLLER_DESCRIPTOR = ControllerDescriptor.class.getSimpleName();

   /** . */
   private static final String CONTROLLER_PARAMETER = ControllerParameter.class.getSimpleName();

   /** . */
   private static final String PHASE = Phase.class.getSimpleName();

   /** . */
   private static final String TOOLS = Tools.class.getSimpleName();

   /** . */
   private static final String RESPONSE = Response.Action.Render.class.getSimpleName();

   /** . */
   public static final String CARDINALITY = Cardinality.class.getSimpleName();

   /** . */
   ProcessingContext env;

   /** . */
   Map<String, TemplateProvider> providers;

   /** . */
   private Map<String, String> moduleConfig;

   /** . */
   private Map<ElementHandle.Package, TemplateResolver> templateRepositoryMap;

   /** . */
   private MetaModel metaModel;

   public ModelResolver()
   {
      this.moduleConfig = new HashMap<String, String>();
      this.metaModel = new MetaModel();
      this.templateRepositoryMap = new HashMap<ElementHandle.Package, TemplateResolver>();
   }

   @Override
   public void postActivate(ProcessingContext env)
   {
      // Discover the template providers
      ServiceLoader<TemplateProvider> loader = ServiceLoader.load(TemplateProvider.class, TemplateProvider.class.getClassLoader());
      Map<String, TemplateProvider> providers = new HashMap<String, TemplateProvider>();
      for (TemplateProvider provider : loader)
      {
         // Get extension
         String pkgName = provider.getClass().getPackage().getName();

         //
         Matcher matcher = PROVIDER_PKG_PATTERN.matcher(pkgName);
         if (matcher.matches())
         {
            String extension = matcher.group(1);
            providers.put(extension, provider);
         }
      }

      //
      this.env = env;
      this.providers = providers;

      //
      metaModel.postActivate(env);
   }

   @Override
   public void processControllerMethod(ExecutableElement methodElt, String annotationName, Map<String, Object> annotationValues) throws CompilationException
   {
      metaModel.processControllerMethod(methodElt, annotationName, annotationValues);
   }

   @Override
   public void processDeclarationTemplate(VariableElement variableElt, String annotationName, Map<String, Object> annotationValues) throws CompilationException
   {
      metaModel.processDeclarationTemplate(variableElt, annotationName, annotationValues);
   }

   @Override
   public void processApplication(PackageElement packageElt, String annotationName, Map<String, Object> annotationValues) throws CompilationException
   {
      metaModel.processApplication(packageElt, annotationName, annotationValues);
   }

   @Override
   public void postProcess() throws CompilationException
   {
      log.log("Meta model post processing");
      metaModel.postProcess();

      //
      for (MetaModelEvent event : metaModel.popEvents())
      {
         log.log("Processing meta model event " + event.getType() + " " + event.getObject());
         processEvent(event);
      }

      //
      log.log("Processing templates");
      processTemplates();
   }

   private void processEvent(MetaModelEvent event) throws CompilationException
   {
      MetaModelObject obj = event.getObject();
      switch (event.getType())
      {
         case MetaModelEvent.AFTER_ADD:
            if (obj instanceof ApplicationMetaModel)
            {
               ApplicationMetaModel application = (ApplicationMetaModel)obj;
               moduleConfig.put(application.getFQN().getSimpleName(), application.getFQN().getFullName());
               templateRepositoryMap.put(application.getHandle(), new TemplateResolver(application));
               emitApplication(application);
            }
            else if (obj instanceof ControllerMetaModel)
            {
               emitController((ControllerMetaModel)obj);
            }
            else if (obj instanceof TemplateMetaModel)
            {
               //
            }
            else if (obj instanceof TemplateRefMetaModel)
            {
               //
            }
            else
            {
               throw new UnsupportedOperationException("Not yet supported add: " + obj);
            }
            break;
         case MetaModelEvent.BEFORE_REMOVE:
            if (obj instanceof ApplicationMetaModel)
            {
               throw new UnsupportedOperationException("Not yet supported remove: " + obj);
            }
            else if (obj instanceof ControllerMetaModel)
            {
               //
            }
            else if (obj instanceof TemplateMetaModel)
            {
               //
            }
            else if (obj instanceof TemplateRefMetaModel)
            {
               //
            }
            else
            {
               throw new UnsupportedOperationException("Not yet supported add: " + obj);
            }
            break;
         case MetaModelEvent.UPDATED:
            if (obj instanceof ApplicationMetaModel)
            {
               //
            }
            if (obj instanceof ControllerMetaModel)
            {
               emitController((ControllerMetaModel)obj);
            }
            else if (obj instanceof TemplateMetaModel)
            {
               //
            }
            else if (obj instanceof TemplateRefMetaModel)
            {
               //
            }
            else
            {
               throw new UnsupportedOperationException("Not yet supported update: " + obj);
            }
            break;
      }
   }

   private void processTemplates()
   {
      for (Map.Entry<ElementHandle.Package, TemplateResolver> entry : templateRepositoryMap.entrySet())
      {
         TemplateResolver repo = entry.getValue();
         repo.process(this);
      }
   }

   @Override
   public void prePassivate() throws CompilationException
   {
      log.log("Passivating meta model");
      metaModel.prePassivate();

      //
      log.log("Emitting config");
      emitConfig();

      //
      log.log("Passivating templates");
      for (TemplateResolver repo : templateRepositoryMap.values())
      {
         repo.prePassivate();
      }

      //
      this.providers = null;
      this.env = null;
   }

   private void emitConfig()
   {
      JSON json = new JSON();
      json.add(moduleConfig);

      // Module config
      Writer writer = null;
      try
      {
         //
         FileObject fo = env.createResource(StandardLocation.CLASS_OUTPUT, "org.juzu", "config.json");
         writer = fo.openWriter();
         json.toString(writer);
      }
      catch (IOException e)
      {
         throw new CompilationException(e, CompilationErrorCode.CANNOT_WRITE_CONFIG);
      }
      finally
      {
         Tools.safeClose(writer);
      }

      // Application configs
      for (ApplicationMetaModel application : metaModel.getApplications())
      {
         json.clear();
         ArrayList<String> controllers = new ArrayList<String>();
         for (ControllerMetaModel controller : application.getControllers())
         {
            controllers.add(controller.getHandle().getFQN().getFullName() + "_");
         }
         json.add("controllers", controllers);
         TemplateResolver repo = templateRepositoryMap.get(application.getHandle());
         if (repo != null)
         {
            ArrayList<String> templates = new ArrayList<String>();
            for (Template template : repo.getTemplates())
            {
               templates.add(template.getFQN().getFullName());
            }
            json.add("templates", templates);
         }

         //
         writer = null;
         try
         {
            FileObject fo = env.createResource(StandardLocation.CLASS_OUTPUT, application.getFQN().getPackageName(), "config.json");
            writer = fo.openWriter();
            json.toString(writer);
         }
         catch (IOException e)
         {
            throw new CompilationException(e, env.get(application.getHandle()), CompilationErrorCode.CANNOT_WRITE_APPLICATION_CONFIG, application.getFQN());
         }
         finally
         {
            Tools.safeClose(writer);
         }
      }
   }

   private void emitApplication(ApplicationMetaModel application) throws CompilationException
   {
      PackageElement elt = env.get(application.getHandle());
      FQN fqn = application.getFQN();

      //
      Writer writer = null;
      try
      {
         JavaFileObject applicationFile = env.createSourceFile(fqn.getFullName(), elt);
         writer = applicationFile.openWriter();

         writer.append("package ").append(fqn.getPackageName()).append(";\n");

         // Imports
         writer.append("import ").append(Tools.getImport(ApplicationDescriptor.class)).append(";\n");

         // Open class
         writer.append("public class ").append(fqn.getSimpleName()).append(" {\n");

         // Singleton
         writer.append("public static final ").append(APPLICATION_DESCRIPTOR).append(" DESCRIPTOR = new ").append(APPLICATION_DESCRIPTOR).append("(");
         writer.append(fqn.getSimpleName()).append(".class,");
         writer.append(application.getDefaultController() != null ? (application.getDefaultController() + ".class") : "null");
         writer.append(",");
         writer.append(application.getEscapeXML() != null ? Boolean.toString(application.getEscapeXML()) : "null");
         writer.append(",");
         writer.append("\"").append(application.getTemplatesQN()).append("\"");
         writer.append(");\n");

         // Close class
         writer.append("}\n");

         //
         log.log("Generated application " + fqn.getFullName() + " as " + applicationFile.toUri());
      }
      catch (IOException e)
      {
         throw new CompilationException(e, elt, CompilationErrorCode.CANNOT_WRITE_APPLICATION, application.getFQN());
      }
      finally
      {
         Tools.safeClose(writer);
      }
   }

   private void emitController(ControllerMetaModel controller) throws CompilationException
   {
      FQN fqn = controller.getHandle().getFQN();
      Element origin = env.get(controller.getHandle());
      List<MethodMetaModel> methods = controller.getMethods();
      Writer writer = null;
      try
      {
         JavaFileObject file = env.createSourceFile(fqn.getFullName() + "_", origin);
         writer = file.openWriter();

         //
         writer.append("package ").append(fqn.getPackageName()).append(";\n");

         // Imports
         writer.append("import ").append(Tools.getImport(ControllerMethod.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(ControllerParameter.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(Tools.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(Arrays.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(Phase.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(URLBuilder.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(InternalApplicationContext.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(MimeContext.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(ActionContext.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(Response.Action.Render.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(ControllerDescriptor.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(Generated.class)).append(";\n");
         writer.append("import ").append(Tools.getImport(Cardinality.class)).append(";\n");

         // Open class
         writer.append("@Generated(value={})\n");
         writer.append("public class ").append(fqn.getSimpleName()).append("_ {\n");

         //
         int index = 0;
         for (MethodMetaModel method : methods)
         {
            String methodRef = "method_" + index++;

            // Method constant
            writer.append("private static final ").append(CONTROLLER_METHOD).append(" ").append(methodRef).append(" = ");
            writer.append("new ").append(CONTROLLER_METHOD).append("(");
            if (method.getId() != null)
            {
               writer.append("\"").append(method.getId()).append("\",");
            }
            else
            {
               writer.append("null,");
            }
            writer.append(PHASE).append(".").append(method.getPhase().name()).append(",");
            writer.append(fqn.getFullName()).append(".class").append(",");
            writer.append(TOOLS).append(".safeGetMethod(").append(fqn.getFullName()).append(".class,\"").append(method.getName()).append("\"");
            for (String parameterType : method.getParameterTypes())
            {
               writer.append(",").append(parameterType).append(".class");
            }
            writer.append(")");
            writer.append(", Arrays.<").append(CONTROLLER_PARAMETER).append(">asList(");
            for (int i = 0;i < method.getParameterNames().size();i++)
            {
               if (i > 0)
               {
                  writer.append(",");
               }
               String parameterName = method.getParameterNames().get(i);
               Cardinality parameterCardinality = method.getParameterCardinalities().get(i);
               writer.append("new ").
                  append(CONTROLLER_PARAMETER).append('(').
                  append('"').append(parameterName).append('"').append(',').
                  append(CARDINALITY).append('.').append(parameterCardinality.name()).
                  append(')');
            }
            writer.append(")");
            writer.append(");\n");

            // Render builder literal
            if (method.getPhase() == Phase.RENDER)
            {
               writer.append("public static ").append(RESPONSE).append(" ").append(method.getName()).append("(");
               for (int j = 0; j < method.getParameterTypes().size(); j++)
               {
                  if (j > 0)
                  {
                     writer.append(',');
                  }
                  writer.append(method.getParameterTypes().get(j)).append(" ").append(method.getParameterNames().get(j));
               }
               writer.append(") { return ((ActionContext)InternalApplicationContext.getCurrentRequest()).createResponse(").append(methodRef);
               switch (method.getParameterTypes().size())
               {
                  case 0:
                     break;
                  case 1:
                     writer.append(",(Object)").append(method.getParameterNames().get(0));
                     break;
                  default:
                     writer.append(",new Object[]{");
                     for (int j = 0; j < method.getParameterNames().size();j++)
                     {
                        if (j > 0)
                        {
                           writer.append(",");
                        }
                        writer.append(method.getParameterNames().get(j));
                     }
                     writer.append("}");
                     break;
               }
               writer.append("); }\n");
            }

            // URL builder literal
            writer.append("public static URLBuilder ").append(method.getName()).append("URL").append("(");
            for (int j = 0; j < method.getParameterTypes().size(); j++)
            {
               if (j > 0)
               {
                  writer.append(',');
               }
               writer.append(method.getParameterTypes().get(j)).append(" ").append(method.getParameterNames().get(j));
            }
            writer.append(") { return ((MimeContext)InternalApplicationContext.getCurrentRequest()).createURLBuilder(").append(methodRef);
            switch (method.getParameterNames().size())
            {
               case 0:
                  break;
               case 1:
                  writer.append(",(Object)").append(method.getParameterNames().get(0));
                  break;
               default:
                  writer.append(",new Object[]{");
                  for (int j = 0;j < method.getParameterNames().size();j++)
                  {
                     if (j > 0)
                     {
                        writer.append(",");
                     }
                     writer.append(method.getParameterNames().get(j));
                  }
                  writer.append("}");
                  break;
            }
            writer.append("); }\n");
         }

         //
         writer.append("public static final ").append(CONTROLLER_DESCRIPTOR).append(" DESCRIPTOR = new ").append(CONTROLLER_DESCRIPTOR).append("(");
         writer.append(fqn.getSimpleName()).append(".class,Arrays.<").append(CONTROLLER_METHOD).append(">asList(");
         for (int j = 0;j < methods.size();j++)
         {
            if (j > 0)
            {
               writer.append(',');
            }
            writer.append("method_").append(Integer.toString(j));
         }
         writer.append(")");
         writer.append(");\n");

         // Close class
         writer.append("}\n");

         //
         log.log("Generated controller companion " + fqn.getFullName() + "_" + " as " + file.toUri());
      }
      catch (IOException e)
      {
         throw new CompilationException(e, origin, CompilationErrorCode.CANNOT_WRITE_CONTROLLER_COMPANION, controller.getHandle().getFQN());
      }
      finally
      {
         Tools.safeClose(writer);
      }
   }
}
