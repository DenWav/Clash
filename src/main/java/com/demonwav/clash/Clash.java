package com.demonwav.clash;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import sun.misc.Unsafe;

public class Clash {

    private static final UnsupportedOperationException TODO = new UnsupportedOperationException();

    public static <T> T init(final Class<T> clazz, final String[] args) {
        final T t;
        try {
            //noinspection unchecked
            t = (T) getUnsafeNoSecurity().allocateInstance(clazz);
        } catch (InstantiationException e) {
            throw new ClashException(e);
        }

        final Field[] fields = removeUnusedFields(getAllFields(clazz));
        verifyArgNames(fields);
        final Map<String, Field> argMap = mapArgumentsToFields(fields);

        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("-")) {
                continue;
            }

            final String argument;
            final String value;

            if (args[i].startsWith("--")) {
                final int index = args[i].indexOf('=');
                if (index == -1) {
                    throw new ClashException("long-form arguments must specify value with '='!");
                }

                argument = args[i].substring(2, index);

                if (index == args[i].length()) {
                    value = "";
                } else {
                    value = args[i].substring(index + 1);
                }
            } else {
                argument = args[i].substring(1);
                value = args[++i];
            }

            final Field field = argMap.get(argument);
            if (field == null) {
                throw new ClashException("Unknown argument: " + argument);
            }

            argMap.entrySet().removeIf(entry -> entry.getValue() == field);

            try {
                initializeValue(field, value, t);
            } catch (IllegalAccessException | NoSuchFieldException | InstantiationException e) {
                throw new ClashException(e);
            }
        }

        for (Field field : argMap.values()) {
            final Argument argument = field.getAnnotation(Argument.class);
            assert argument != null;

            try {
                if (!argument.defaultCreator().isInterface()) {
                    field.set(t, argument.defaultCreator().newInstance().createDefault());
                } else if (!argument.defaultValue().equals("")) {
                    initializeValue(field, argument.defaultValue(), t);
                } else if (argument.required()) {
                    throw new ClashException("Required argument not provided: " + argument.shortName());
                }
            } catch (IllegalAccessException | InstantiationException | NoSuchFieldException e) {
                throw new ClashException(e);
            }
        }

        return t;
    }

    private static Field[] getAllFields(final Class<?> clazz) {
        final Field[] fields = clazz.getDeclaredFields();
        final Class<?> superClazz = clazz.getSuperclass();

        if (superClazz != null) {
            final Field[] moreFields = getAllFields(superClazz);
            if (moreFields.length == 0) {
                return fields;
            }

            final Field[] newFields = new Field[fields.length + moreFields.length];
            System.arraycopy(fields, 0, newFields, 0, fields.length);
            System.arraycopy(moreFields, 0, newFields, fields.length, moreFields.length);

            return newFields;
        }
        return fields;
    }

    private static Field[] removeUnusedFields(final Field[] fields) {
        final Field[] result = new Field[fields.length];

        int counter = 0;
        for (Field field : fields) {
            if (Modifier.isTransient(field.getModifiers())) {
                continue;
            }

            if (field.getAnnotation(Argument.class) == null) {
                continue;
            }

            result[counter++] = field;
        }

        final Field[] finalResult = new Field[counter];
        System.arraycopy(result, 0, finalResult, 0, counter);
        return finalResult;
    }

    private static void verifyArgNames(final Field[] fields) {
        final String dashMessage = "Argument name must not begin with a dash (-)! : ";
        final String whitespaceMessage = "Argument name must not contain whitespace! : ";
        final Pattern pattern = Pattern.compile("\\s");

        for (Field field : fields) {
            final Argument annotation = field.getAnnotation(Argument.class);
            assert annotation != null;

            if (annotation.shortName().startsWith("-")) {
                throw new ClashException(dashMessage + annotation.shortName());
            }
            if (pattern.matcher(annotation.shortName()).find()) {
                throw new ClashException(whitespaceMessage + annotation.shortName());
            }

            for (String name : annotation.longNames()) {
                if (name.startsWith("-")) {
                    throw new ClashException(dashMessage + name);
                }

                if (pattern.matcher(name).find()) {
                    throw new ClashException(whitespaceMessage + name);
                }
            }
        }
    }

    private static Map<String, Field> mapArgumentsToFields(final Field[] fields) {
        final Map<String, Field> map = new HashMap<>(fields.length);

        for (Field field : fields) {
            final Argument annotation = field.getAnnotation(Argument.class);
            assert annotation != null;

            map.put(annotation.shortName(), field);

            for (String name : annotation.longNames()) {
                map.put(name, field);
            }
        }

        return map;
    }

    private static void initializeValue(final Field field, final String value, final Object object)
            throws IllegalAccessException, NoSuchFieldException, InstantiationException {

        final Class<?> type = field.getType();
        field.setAccessible(true);

        boolean isFinal = false;
        try {
            if (Modifier.isFinal(field.getModifiers())) {
                isFinal = true;
                // We can set final fields, so why not
                final Field modifiers = field.getClass().getDeclaredField("modifiers");
                modifiers.setAccessible(true);
                modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            }

            final Argument annotation = field.getAnnotation(Argument.class);
            assert annotation != null;

            if (!annotation.initializer().isInterface()) {
                final Initializer initializer = annotation.initializer().newInstance();
                field.set(object, initializer.initialize(value));
                // primitives aren't covered by their boxed classes in these checks
                // String first because it's probably very common
            } else if (type.isAssignableFrom(String.class) || type.isAssignableFrom(CharSequence.class)) {
                field.set(object, value);
                // Primitives
            } else if (type.isAssignableFrom(byte.class)) {
                field.setByte(object, Byte.valueOf(value));
            } else if (type.isAssignableFrom(short.class)) {
                field.setShort(object, Short.valueOf(value));
            } else if (type.isAssignableFrom(int.class)) {
                field.setInt(object, Integer.valueOf(value));
            } else if (type.isAssignableFrom(long.class)) {
                field.setLong(object, Long.valueOf(value));
            } else if (type.isAssignableFrom(float.class)) {
                field.setFloat(object, Float.valueOf(value));
            } else if (type.isAssignableFrom(double.class)) {
                field.setDouble(object, Double.valueOf(value));
            } else if (type.isAssignableFrom(boolean.class)) {
                field.setBoolean(object, Boolean.valueOf(value));
                // Boxed classes
            } else if (type.isAssignableFrom(Byte.class)) {
                field.set(object, Byte.valueOf(value));
            } else if (type.isAssignableFrom(Short.class)) {
                field.set(object, Short.valueOf(value));
            } else if (type.isAssignableFrom(Integer.class)) {
                field.set(object, Integer.valueOf(value));
            } else if (type.isAssignableFrom(Long.class)) {
                field.set(object, Long.valueOf(value));
            } else if (type.isAssignableFrom(Float.class)) {
                field.set(object, Float.valueOf(value));
            } else if (type.isAssignableFrom(Double.class)) {
                field.set(object, Double.valueOf(value));
            } else if (type.isAssignableFrom(Boolean.class)) {
                field.set(object, Boolean.valueOf(value));
                // Other Number children
            } else if (type.isAssignableFrom(BigInteger.class)) {
                field.set(object, new BigInteger(value, 10));
            } else if (type.isAssignableFrom(BigDecimal.class)) {
                field.set(object, new BigDecimal(value));
            } else if (type.isAssignableFrom(AtomicInteger.class)) {
                field.set(object, new AtomicInteger(Integer.valueOf(value)));
            } else if (type.isAssignableFrom(AtomicLong.class)) {
                field.set(object, new AtomicLong(Long.valueOf(value)));
                // Primitive arrays
            } else if (type.isAssignableFrom(byte[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(short[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(int[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(long[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(float[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(double[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(boolean[].class)) {
                throw TODO;
                // Boxed arrays
            } else if (type.isAssignableFrom(Byte[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(Short[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(Integer[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(Long[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(Float[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(Double[].class)) {
                throw TODO;
            } else if (type.isAssignableFrom(Boolean[].class)) {
                throw TODO;
                // Other Number arrays
            } else if (type.isAssignableFrom(BigInteger.class)) {
                throw TODO;
            } else if (type.isAssignableFrom(BigDecimal.class)) {
                throw TODO;
            } else if (type.isAssignableFrom(AtomicInteger.class)) {
                throw TODO;
            } else if (type.isAssignableFrom(AtomicLong.class)) {
                throw TODO;
                // Other arrays
            } else if (type.isAssignableFrom(String[].class)) {
                throw TODO;
                // Other cases
            } else {
                throw TODO;
            }
        } finally {
            if (isFinal) {
                // reset it to final
                final Field modifiers = field.getClass().getDeclaredField("modifiers");
                modifiers.setAccessible(true);
                modifiers.setInt(field, field.getModifiers() | Modifier.FINAL);
            }
        }
    }

    // I'm evil
    private static Unsafe getUnsafeNoSecurity() {
        try {
            final Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return (Unsafe) theUnsafe.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ClashException(e);
        }
    }
}