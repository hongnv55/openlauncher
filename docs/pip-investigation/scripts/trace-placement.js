'use strict';

Java.perform(function () {
    const FILTERS = [
        'com.openlauncher.pipprobe',
        'app.morphe.android.apps.maps',
        'app.morphe.android.youtube',
        'app.revanced.android.youtube'
    ];

    function text(value) {
        try {
            return value === null || value === undefined ? 'null' : String(value);
        } catch (_) {
            return '<unprintable>';
        }
    }

    function relevant(value) {
        const rendered = text(value);
        return FILTERS.some(function (name) { return rendered.indexOf(name) !== -1; });
    }

    function log(message) {
        console.log('[PIP-PLACEMENT] ' + message);
    }

    function value(object, fieldName) {
        try {
            return text(object[fieldName].value);
        } catch (_) {
            return '<unavailable>';
        }
    }

    const ActivityRecord = Java.use('com.android.server.am.ActivityRecord');
    const canLaunch = ActivityRecord.canBeLaunchedOnDisplay.overload('int');
    canLaunch.implementation = function (displayId) {
        const result = canLaunch.call(this, displayId);
        if (relevant(this)) {
            log('ActivityRecord.canBeLaunchedOnDisplay record=' + text(this)
                + ' requestedDisplay=' + displayId
                + ' launchedFromPid=' + value(this, 'launchedFromPid')
                + ' launchedFromUid=' + value(this, 'launchedFromUid')
                + ' task=' + value(this, 'task')
                + ' result=' + result);
        }
        return result;
    };

    const ActivityStarter = Java.use('com.android.server.am.ActivityStarter');
    const reusable = ActivityStarter.getReusableIntentActivity.overload();
    reusable.implementation = function () {
        const result = reusable.call(this);
        let start = null;
        try { start = this.mStartActivity.value; } catch (_) {}
        if (relevant(start) || relevant(result)) {
            log('ActivityStarter.getReusableIntentActivity start=' + text(start)
                + ' result=' + text(result));
        }
        return result;
    };

    const moveFront = ActivityStarter.setTargetStackAndMoveToFrontIfNeeded.overload(
        'com.android.server.am.ActivityRecord'
    );
    moveFront.implementation = function (record) {
        const result = moveFront.call(this, record);
        if (relevant(record)) {
            log('ActivityStarter.setTargetStackAndMoveToFrontIfNeeded record=' + text(record)
                + ' result=' + text(result));
        }
        return result;
    };

    const Supervisor = Java.use('com.android.server.am.ActivityStackSupervisor');
    const canPlace = Supervisor.canPlaceEntityOnDisplay.overload(
        'int', 'boolean', 'int', 'int', 'android.content.pm.ActivityInfo'
    );
    canPlace.implementation = function (displayId, resizeable, callingPid, callingUid, info) {
        const result = canPlace.call(this, displayId, resizeable, callingPid, callingUid, info);
        if (relevant(info)) {
            log('ActivityStackSupervisor.canPlaceEntityOnDisplay activity=' + text(info)
                + ' display=' + displayId + ' resizeable=' + resizeable
                + ' callingPid=' + callingPid + ' callingUid=' + callingUid
                + ' supportsMultiDisplay=' + value(this, 'mSupportsMultiDisplay')
                + ' result=' + result);
        }
        return result;
    };

    const callerAllowed = Supervisor.isCallerAllowedToLaunchOnDisplay.overload(
        'int', 'int', 'int', 'android.content.pm.ActivityInfo'
    );
    callerAllowed.implementation = function (callingPid, callingUid, displayId, info) {
        const result = callerAllowed.call(this, callingPid, callingUid, displayId, info);
        if (relevant(info)) {
            log('ActivityStackSupervisor.isCallerAllowedToLaunchOnDisplay activity=' + text(info)
                + ' display=' + displayId + ' callingPid=' + callingPid
                + ' callingUid=' + callingUid + ' result=' + result);
        }
        return result;
    };

    Supervisor.getLaunchStack.overloads.forEach(function (overload) {
        overload.implementation = function () {
            const args = Array.prototype.slice.call(arguments);
            const result = overload.call.apply(overload, [this].concat(args));
            if (args.some(relevant) || relevant(result)) {
                log('ActivityStackSupervisor.getLaunchStack args=['
                    + args.map(text).join(', ') + '] result=' + text(result)
                    + ' resultDisplay=' + value(result, 'mDisplayId'));
            }
            return result;
        };
    });

    const TaskRecord = Java.use('com.android.server.am.TaskRecord');
    TaskRecord.reparent.overloads.forEach(function (overload) {
        overload.implementation = function () {
            const args = Array.prototype.slice.call(arguments);
            const shouldLog = relevant(this) || args.some(relevant);
            const result = overload.call.apply(overload, [this].concat(args));
            if (shouldLog) {
                log('TaskRecord.reparent task=' + text(this) + ' args=['
                    + args.map(text).join(', ') + '] result=' + text(result));
            }
            return result;
        };
    });

    log('hooks installed; filters=' + FILTERS.join(','));
});
