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

package org.juzu.impl.model.meta;

import org.juzu.Action;
import org.juzu.Application;
import org.juzu.Path;
import org.juzu.Resource;
import org.juzu.View;
import org.juzu.impl.compiler.BaseProcessor;
import org.juzu.impl.compiler.CompilationException;
import org.juzu.impl.compiler.ElementHandle;
import org.juzu.impl.model.processor.ModelHandler;
import org.juzu.impl.model.processor.ProcessingContext;
import org.juzu.impl.utils.FQN;
import org.juzu.impl.utils.JSON;
import org.juzu.impl.utils.Logger;
import org.juzu.impl.utils.QN;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/** @author <a href="mailto:julien.viet@exoplatform.com">Julien Viet</a> */
public class MetaModel extends ModelHandler
{

   /** . */
   LinkedHashMap<ElementHandle.Class, ControllerMetaModel> controllers = new LinkedHashMap<ElementHandle.Class, ControllerMetaModel>();

   /** . */
   LinkedHashMap<ElementHandle.Field, TemplateRefMetaModel> templates = new LinkedHashMap<ElementHandle.Field, TemplateRefMetaModel>();

   /** . */
   LinkedHashMap<ElementHandle.Package, ApplicationMetaModel> applications = new LinkedHashMap<ElementHandle.Package, ApplicationMetaModel>();

   /** . */
   ProcessingContext env;

   /** . */
   private final LinkedList<MetaModelEvent> queue = new LinkedList<MetaModelEvent>();

   /** . */
   private Logger log;

   public JSON toJSON()
   {
      JSON json = new JSON();
      json.add("templates", templates.values());
      json.add("applications", applications.values());
      json.add("controllers", controllers.values());
      return json;
   }

   //

   public ApplicationMetaModel addApplication(String packageName, String applicationName)
   {
      return addApplication(ElementHandle.Package.create(new QN(packageName)), applicationName, null, null);
   }

   public TemplateRefMetaModel addTemplateRef(String className, String fieldName, String path)
   {
      return addTemplateRef(ElementHandle.Field.create(new FQN(className), fieldName), path);
   }

   public ControllerMetaModel addController(String className)
   {
      return addController(ElementHandle.Class.create(new FQN(className)));
   }

   //

   public Collection<ApplicationMetaModel> getApplications()
   {
      return new ArrayList<ApplicationMetaModel>(applications.values());
   }

   public ApplicationMetaModel getApplication(ElementHandle.Package handle)
   {
      return applications.get(handle);
   }

   public ApplicationMetaModel addApplication(ElementHandle.Package handle, String applicationName, String defaultController, Boolean escapeXML)
   {
      if (applications.containsKey(handle))
      {
         throw new IllegalStateException("Contains already application " + handle);
      }
      ApplicationMetaModel application = new ApplicationMetaModel(this, handle, applicationName, defaultController, escapeXML);
      applications.put(handle, application);
      queue(new MetaModelEvent(MetaModelEvent.AFTER_ADD, application));
      return application;
   }

   private TemplateRefMetaModel addTemplateRef(ElementHandle.Field handle, String path)
   {
      if (templates.containsKey(handle))
      {
         throw new IllegalStateException();
      }
      TemplateRefMetaModel ref = new TemplateRefMetaModel(handle, path);
      templates.put(handle, ref);
      return ref;
   }

   private ControllerMetaModel addController(ElementHandle.Class handle)
   {
      if (controllers.containsKey(handle))
      {
         throw new IllegalStateException();
      }
      ControllerMetaModel controller = new ControllerMetaModel(this, handle);
      controllers.put(handle, controller);
      return controller;
   }

   //

   @Override
   public void postActivate(ProcessingContext env)
   {
      this.env = env;
      this.log = BaseProcessor.getLogger(MetaModel.class);

      //
      gcApplications();

      //
      gcControllers();

      //
      gcTemplateRefs();
   }

   @Override
   public void processControllerMethod(
      ExecutableElement methodElt,
      String annotationName,
      Map<String, Object> annotationValues) throws CompilationException
   {
      TypeElement controllerElt = (TypeElement)methodElt.getEnclosingElement();
      ElementHandle.Class handle = ElementHandle.Class.create(controllerElt);
      ControllerMetaModel controller = controllers.get(handle);
      if (controller == null)
      {
         controller = addController(handle);
      }
      controller.addMethod(
         methodElt,
         annotationName,
         annotationValues);
   }

   @Override
   public void processDeclarationTemplate(
      VariableElement variableElt,
      String annotationName,
      Map<String, Object> annotationValues) throws CompilationException
   {
      String path = (String)annotationValues.get("value");
      ElementHandle.Field handle = ElementHandle.Field.create(variableElt);
      TemplateRefMetaModel ref = templates.get(handle);
      if (ref == null)
      {
         addTemplateRef(handle, path);
      }
      else if (ref.path.equals(path))
      {
         // OK
      }
      else
      {
         // We do have a template
         if (ref.template != null)
         {
            // Remove the ref, the template will be garbaged in a later phase
            if (ref.template.refs.remove(ref.handle) == null)
            {
               throw new AssertionError();
            }

            // Assign null, if such template already exist it will be resolved in a later phase
            ref.template = null;
         }

         // Update the ref
         ref.path = path;
      }
   }

   @Override
   public void processApplication(
      PackageElement packageElt,
      String annotationName,
      Map<String, Object> annotationValues) throws CompilationException
   {
      TypeMirror defaultControllerElt = (TypeMirror)annotationValues.get("defaultController");
      String defaultController = defaultControllerElt != null ? defaultControllerElt.toString() : null;
      Boolean escapeXML = (Boolean)annotationValues.get("escapeXML");
      String name = (String)annotationValues.get("name");
      if (name == null)
      {
         String s = packageElt.getSimpleName().toString();
         name = Character.toUpperCase(s.charAt(0)) + s.substring(1) + "Application";
      }
      ElementHandle.Package handle = ElementHandle.Package.create(packageElt);
      ApplicationMetaModel application = applications.get(handle);
      if (application == null)
      {
         addApplication(handle, name, defaultController, escapeXML);
      }
      else
      {
         application.modified = true;
      }
   }

   /**
    * .
    */
   @Override
   public void postProcess()
   {
      resolveTemplateRefs();

      //
      resolveControllers();

      //
      resolveApplications();
   }

   /**
    * .
    */
   @Override
   public void prePassivate()
   {
      gcTemplates();

      //
      env = null;
      log = null;
   }

   private void resolveApplications()
   {
      for (ApplicationMetaModel application : applications.values())
      {
         if (application.modified)
         {
            queue(new MetaModelEvent(MetaModelEvent.UPDATED, application));
            application.modified = false;
         }
      }
   }

   private void resolveControllers()
   {
      for (ControllerMetaModel controller : controllers.values())
      {
         if (controller.application == null)
         {
            PackageElement packageElt = env.getPackageOf(env.get(controller.handle));
            QN packageQN = new QN(packageElt.getQualifiedName());
            for (ApplicationMetaModel application : applications.values())
            {
               if (application.fqn.getPackageName().isPrefix(packageQN))
               {
                  application.addController(controller);
                  controller.modified = false;
               }
            }
         }
         else
         {
            if (controller.modified)
            {
               queue(new MetaModelEvent(MetaModelEvent.UPDATED, controller));
               controller.modified = false;
            }
         }
      }
   }

   /**
    * Takes care of templates ref having a null template and try to assign each of them.
    */
   private void resolveTemplateRefs()
   {
      for (TemplateRefMetaModel ref : templates.values())
      {
         if (ref.template == null)
         {
            VariableElement variableElt = env.get(ref.handle);
            PackageElement packageElt = env.getPackageOf(variableElt);
            QN packageQN = new QN(packageElt.getQualifiedName());
            for (ApplicationMetaModel application : applications.values())
            {
               if (application.fqn.getPackageName().isPrefix(packageQN))
               {
                  TemplateMetaModel template = application.templates.get(ref.path);
                  if (template == null)
                  {
                     template = application.addTemplate(ref);
                  }
                  template.addRef(ref);
               }
            }
         }
      }
   }

   private void gcApplications()
   {
      for (Iterator<ApplicationMetaModel> i = applications.values().iterator();i.hasNext();)
      {
         ApplicationMetaModel application = i.next();
         PackageElement packageElt = env.get(application.handle);
         if (packageElt == null)
         {
            throw new UnsupportedOperationException();
         }
         else
         {
            // Cleanup
            boolean found = false;
            for (AnnotationMirror annotationMirror : packageElt.getAnnotationMirrors())
            {
               if (found = ((TypeElement)annotationMirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(Application.class.getName()))
               {
                  break;
               }
            }
            if (!found)
            {
               application.model.queue(new MetaModelEvent(MetaModelEvent.BEFORE_REMOVE, application));

               //
               for (ControllerMetaModel controller : application.getControllers())
               {
                  application.removeController(controller);
               }

               //
               for (TemplateMetaModel template : application.getTemplates())
               {
                  application.removeTemplate(template);
               }

               // Remove application itself
               i.remove();
            }
         }
      }
   }

   private void gcControllers()
   {
      for (Iterator<ControllerMetaModel> i = controllers.values().iterator();i.hasNext();)
      {
         ControllerMetaModel controller = i.next();
         for (Iterator<MethodMetaModel> j = controller.methods.values().iterator();j.hasNext();)
         {
            MethodMetaModel method = j.next();
            ExecutableElement methodElt = env.get(method.handle);
            boolean remove = methodElt == null || (
               methodElt.getAnnotation(View.class) == null &&
               methodElt.getAnnotation(Action.class) == null &&
               methodElt.getAnnotation(Resource.class) == null);
            if (remove)
            {
               j.remove();
            }
         }

         // Remove the controller if it has no methods
         if (controller.methods.isEmpty())
         {
            if (controller.application != null)
            {
               controller.application.removeController(controller);
            }

            //
            i.remove();
         }
      }
   }

   private void gcTemplateRefs()
   {
      for (Iterator<TemplateRefMetaModel> i = templates.values().iterator();i.hasNext();)
      {
         TemplateRefMetaModel ref = i.next();
         VariableElement fieldElt = env.get(ref.handle);
         boolean remove = false;
         if (fieldElt == null)
         {
            log.log("Removing handle " + ref.handle + " that does not exist anymore");
            remove = true;
         }
         else if (fieldElt.getAnnotation(Path.class) == null)
         {
            log.log("Removing handle " + ref.handle + " that is not annoated anymore");
            remove = true;
         }

         //
         if (remove)
         {
            if (ref.template != null)
            {
               ref.template.removeRef(ref);
            }
            i.remove();
         }
      }
   }

   /**
    * Garbage unused templates.
    */
   private void gcTemplates()
   {
      for (ApplicationMetaModel application : applications.values())
      {
         for (TemplateMetaModel template : application.getTemplates())
         {
            if (template.refs.isEmpty())
            {
               application.removeTemplate(template);
            }
         }
      }
   }

   void queue(MetaModelEvent event)
   {
      if (log != null)
      {
         log.log("Queue event " + event.getType() + " " + event.getObject());
      }
      queue.add(event);
   }

   public List<MetaModelEvent> popEvents()
   {
      ArrayList<MetaModelEvent> copy = new ArrayList<MetaModelEvent>(queue);
      queue.clear();
      return copy;
   }

   public MetaModelEvent popEvent()
   {
      return queue.isEmpty() ? null : queue.removeFirst();
   }

   public boolean hasEvents()
   {
      return !queue.isEmpty();
   }
}
