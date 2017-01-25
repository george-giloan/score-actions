/*******************************************************************************
 * (c) Copyright 2017 Hewlett-Packard Development Company, L.P.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License v2.0 which accompany this distribution.
 *
 * The Apache License is available at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *******************************************************************************/
package io.cloudslang.content.database.utils;

import io.cloudslang.content.utils.BooleanUtilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.cloudslang.content.database.constants.DBDefaultValues.AUTH_SQL;
import static io.cloudslang.content.database.constants.DBExceptionValues.*;
import static io.cloudslang.content.database.constants.DBOtherValues.*;
import static io.cloudslang.content.database.utils.Constants.AUTH_WINDOWS;
import static io.cloudslang.content.database.utils.SQLInputsUtils.inCollectionIgnoreCase;
import static io.cloudslang.content.database.utils.SQLInputsUtils.notInCollectionIgnoreCase;
import static io.cloudslang.content.utils.NumberUtilities.isValidInt;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNoneEmpty;

/**
 * Created by victor on 1/23/17.
 */
public class SQLInputsValidator {

    private static final List<String> NO_EMPTY_DATABASE = Arrays.asList(SYBASE_DB_TYPE, MSSQL_DB_TYPE, NETCOOL_DB_TYPE);
    private static final List<String> AUTH_TYPES = Arrays.asList(AUTH_WINDOWS, AUTH_SQL);

    public static List<String> validateSqlCommandInputs(String dbType, String username, String password, String instance, String dbPort, String database, String authenticationType,
                                                        String command, String trustAllRoots, String resultSetType, String resultSetConcurrency) {
        final List<String> validationList = new ArrayList<>();
        validateDbType(dbType, validationList);
        validateNoneEmpty(username, INVALID_USERNAME, validationList);
        validateNoneEmpty(password, INVALID_PASSWORD, validationList);
        validateNoneEmpty(command, INVALID_COMMAND, validationList);
        validateTrustAllRoots(trustAllRoots, validationList);
        validateResultSetType(resultSetType, validationList);
        validateResultSetConcurrency(resultSetConcurrency, validationList);
        validateDbPort(dbPort, validationList);
        if (isValidDbType(dbType)) {
            validateInstance(instance, dbType, validationList);
            validateDbName(database, dbType, validationList);
            validateAuthType(authenticationType, dbType, validationList);
        }
        return validationList;
    }


    public static List<String> validateSqlInputs(final SQLInputs sqlInputs) {
        final List<String> validationList = new ArrayList<>();
        validateIgnoreCase(sqlInputs.getIgnoreCase(), validationList);

//       todo not all cases validateTimeout(sqlInputs.getTimeout(), validationList);
        validateTrustAllRootsRequire(sqlInputs.getTrustAllRoots(), sqlInputs.getTrustStore(), sqlInputs.getTrustStorePassword(), validationList);
        return validationList;
    }

    private static void validateDbPort(final String dbPort, final List<String> validationList) {
        if (isNoneEmpty(dbPort) && !isValidInt(dbPort)) {
            validationList.add(INVALID_DB_PORT);
        }
    }

    private static void validateTrustAllRoots(final String trustAllRoots, final List<String> validationList) {
        if (BooleanUtilities.isValid(trustAllRoots)) {
            validationList.add(INVALID_TRUST_ALL_ROOTS);
        }
    }

    private static void validateDbType(final String dbType, final List<String> validationList) {
        if (isNotValidDbType(dbType)) {
            validationList.add(INVALID_DB_TYPE);
        }
    }

    private static void validateTrustAllRootsRequire(final boolean trustAllRoots, final String trustStore, final String trustStorePassword, final List<String> validationList) {
        if (!trustAllRoots && (isEmpty(trustStore) || isEmpty(trustStorePassword))) {
            validationList.add(INVALID_TRUST_ALL_ROOTS_REQUIRE);
        }
    }

    private static void validateTimeout(final int timeout, final List<String> validationList) {
        if (timeout < 0) {
            validationList.add(INVALID_NEGATIVE_TIMEOUT);
        }
    }

    private static void validateInstance(final String instance, final String dbType, final List<String> validationList) {
        if (isNoneEmpty(instance) && !MSSQL_DB_TYPE.equalsIgnoreCase(dbType)) {
            validationList.add(INVALID_INSTANCE);
        }
    }

    private static void validateAuthType(final String authType, final String dbType, final List<String> validationList) {
        if (notInCollectionIgnoreCase(authType, AUTH_TYPES)) {
            validationList.add(String.format(INVALID_AUTH_TYPE, authType));
        } else if (AUTH_WINDOWS.equalsIgnoreCase(authType) && !MSSQL_DB_TYPE.equalsIgnoreCase(dbType)) {
            validationList.add(INVALID_AUTH_TYPE_WINDOWS);
        }
    }

    private static void validateDbName(final String dbName, final String dbType, final List<String> validationList) {
        if (isEmpty(dbName) && notInCollectionIgnoreCase(dbType, NO_EMPTY_DATABASE)) {
            validationList.add(INVALID_DATABASE);
        }
    }

    private static void validateIgnoreCase(final String ignoreCase, final List<String> validationList) {
        if (!BooleanUtilities.isValid(ignoreCase)) {
            validationList.add(String.format(INVALID_IGNORE_CASE, ignoreCase));
        }
    }

    private static void validateNoneEmpty(final String toValidate, final String exceptionMessage, final List<String> validationList) {
        if (isEmpty(toValidate)) {
            validationList.add(exceptionMessage);
        }
    }

    private static void validateResultSetConcurrency(final String resultSetConcurrency, final List<String> validationList) {
        if (!isValidResultSetConcurrency(resultSetConcurrency)) {
            validationList.add(INVALID_RESULT_SET_CONCURRENCY);
        }
    }

    private static void validateResultSetType(final String resultSetType, final List<String> validationList) {
        if (!isValidResultSetType(resultSetType)) {
            validationList.add(INVALID_RESULT_SET_TYPE);
        }
    }

    public static boolean isValidDbType(final String dbType) {
        return inCollectionIgnoreCase(dbType, DB_PORTS.keySet());
    }

    public static boolean isNotValidDbType(final String dbType) {
        return notInCollectionIgnoreCase(dbType, DB_PORTS.keySet());
    }

    public static boolean isValidResultSetConcurrency(final String resultSetConcurrency) {
        return CONCUR_VALUES.containsKey(resultSetConcurrency);
    }

    public static boolean isValidResultSetType(final String resultSetType) {
        return TYPE_VALUES.containsKey(resultSetType);
    }
}
