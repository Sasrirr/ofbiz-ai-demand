import org.apache.ofbiz.service.ServiceContainer
import org.apache.ofbiz.base.util.UtilDateTime

// Script runner: gradlew "ofbiz --script=runPredictDemand.groovy" -DproductId=WG-1111 -DhorizonDays=14

def pid = System.getProperty('productId', 'WG-1111')
def horizon = Integer.getInteger('horizonDays', 14)

def del = binding.hasVariable('delegator') ? delegator : null
if (!del) {
    throw new IllegalStateException('Delegator not available in script context')
}

def dispatcher = binding.hasVariable('dispatcher') ? dispatcher : ServiceContainer.getLocalDispatcher('default', del)

def ctx = [productId: pid, horizonDays: horizon]
println "Calling predictDemandForProduct with ${ctx}"

def res = dispatcher.runSync('predictDemandForProduct', ctx)
println "Result: ${res}"