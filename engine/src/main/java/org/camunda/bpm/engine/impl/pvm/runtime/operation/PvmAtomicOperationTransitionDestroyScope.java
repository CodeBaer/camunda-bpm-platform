/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.impl.pvm.runtime.operation;

import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.pvm.process.TransitionImpl;
import org.camunda.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;

import java.util.List;
import java.util.logging.Logger;


/**
 * @author Tom Baeyens
 */
public class PvmAtomicOperationTransitionDestroyScope implements PvmAtomicOperation {

  private static Logger log = Logger.getLogger(PvmAtomicOperationTransitionDestroyScope.class.getName());

  public boolean isAsync(PvmExecutionImpl instance) {
    return false;
  }

  @SuppressWarnings("unchecked")
  public void execute(PvmExecutionImpl execution) {

    // calculate the propagating execution
    PvmExecutionImpl propagatingExecution = null;

    ActivityImpl activity = execution.getActivity();
    // if this transition is crossing a scope boundary
    if (activity.isScope()) {

      PvmExecutionImpl parentScopeInstance = null;
      // if this is a concurrent execution crossing a scope boundary
      if (execution.isConcurrent() && !execution.isScope()) {
        // first remove the execution from the current root
        PvmExecutionImpl concurrentRoot = execution.getParent();
        parentScopeInstance = execution.getParent().getParent();

        log.fine("moving concurrent "+execution+" one scope up under "+parentScopeInstance);
        List<PvmExecutionImpl> parentScopeInstanceExecutions = (List<PvmExecutionImpl>) parentScopeInstance.getExecutions();
        List<PvmExecutionImpl> concurrentRootExecutions = (List<PvmExecutionImpl>) concurrentRoot.getExecutions();
        // if the parent scope had only one single scope child
        if (parentScopeInstanceExecutions.size()==1) {
          // it now becomes a concurrent execution
          parentScopeInstanceExecutions.get(0).setConcurrent(true);
        }

        concurrentRootExecutions.remove(execution);
        parentScopeInstanceExecutions.add(execution);
        execution.setParent(parentScopeInstance);
        execution.setActivity(activity);
        propagatingExecution = execution;

        // if there is only a single concurrent execution left
        // in the concurrent root, auto-prune it.  meaning, the
        // last concurrent child execution data should be cloned into
        // the concurrent root.
        if (concurrentRootExecutions.size()==1) {
          PvmExecutionImpl lastConcurrent = concurrentRootExecutions.get(0);
          if (lastConcurrent.isScope()) {
            lastConcurrent.setConcurrent(false);

          } else {
            log.fine("merging last concurrent "+lastConcurrent+" into concurrent root "+concurrentRoot);

            // We can't just merge the data of the lastConcurrent into the concurrentRoot.
            // This is because the concurrent root might be in a takeAll-loop.  So the
            // concurrent execution is the one that will be receiving the take
            concurrentRoot.setActivity(lastConcurrent.getActivity());
            concurrentRoot.setActive(lastConcurrent.isActive());
            lastConcurrent.setReplacedBy(concurrentRoot);
            lastConcurrent.remove();
          }
        }

      } else if (execution.isConcurrent() && execution.isScope()) {
        log.fine("scoped concurrent "+execution+" becomes concurrent and remains under "+execution.getParent());

        // TODO!
        execution.destroy();
        propagatingExecution = execution;

      } else {
        propagatingExecution = execution.getParent();
        propagatingExecution.setActivity(execution.getActivity());
        propagatingExecution.setTransition(execution.getTransition());
        propagatingExecution.setActive(true);
        log.fine("destroy scope: scoped "+execution+" continues as parent scope "+propagatingExecution);
        execution.destroy();
        execution.remove();
      }

    } else {
      propagatingExecution = execution;
    }

    // if there is another scope element that is ended
    ScopeImpl nextOuterScopeElement = activity.getParent();
    TransitionImpl transition = propagatingExecution.getTransition();
    ActivityImpl destination = transition.getDestination();

    if (transitionLeavesNextOuterScope(nextOuterScopeElement, activity, destination)) {
      propagatingExecution.setActivity((ActivityImpl) nextOuterScopeElement);
      propagatingExecution.performOperation(TRANSITION_NOTIFY_LISTENER_END);
    } else {
      // while executing the transition, the activityInstance is 'null'
      // (we are not executing an activity)
      propagatingExecution.setActivityInstanceId(null);
      propagatingExecution.performOperation(TRANSITION_NOTIFY_LISTENER_TAKE);
    }
  }

  public boolean transitionLeavesNextOuterScope(ScopeImpl nextScopeElement, ActivityImpl current, ActivityImpl destination) {
    return !current.isCancelScope() && !current.isConcurrent() && !nextScopeElement.contains(destination);
  }

  public String getCanonicalName() {
    return "transition-destroy-scope";
  }
}
