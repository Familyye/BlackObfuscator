/*
 * Copyright 2012, Google Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jf.dexlib2.dexbacked;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import org.jf.dexlib2.base.reference.BaseTypeReference;
import org.jf.dexlib2.dexbacked.raw.ClassDefItem;
import org.jf.dexlib2.dexbacked.raw.TypeIdItem;
import org.jf.dexlib2.dexbacked.util.AnnotationsDirectory;
import org.jf.dexlib2.dexbacked.util.FixedSizeSet;
import org.jf.dexlib2.dexbacked.util.StaticInitialValueIterator;
import org.jf.dexlib2.dexbacked.util.VariableSizeLookaheadIterator;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;



import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class DexBackedClassDef extends BaseTypeReference implements ClassDef {
    public final DexBackedDexFile dexFile;
    private final int classDefOffset;

    private final int staticFieldsOffset;
    private int instanceFieldsOffset = 0;
    private int directMethodsOffset = 0;
    private int virtualMethodsOffset = 0;

    private final int staticFieldCount;
    private final int instanceFieldCount;
    private final int directMethodCount;
    private final int virtualMethodCount;

    private AnnotationsDirectory annotationsDirectory;

    public DexBackedClassDef(DexBackedDexFile dexFile,
                             int classDefOffset) {
        this.dexFile = dexFile;
        this.classDefOffset = classDefOffset;

        int classDataOffset = dexFile.readSmallUint(classDefOffset + ClassDefItem.CLASS_DATA_OFFSET);
        if (classDataOffset == 0) {
            staticFieldsOffset = -1;
            staticFieldCount = 0;
            instanceFieldCount = 0;
            directMethodCount = 0;
            virtualMethodCount = 0;
        } else {
            DexReader reader = dexFile.readerAt(classDataOffset);
            staticFieldCount = reader.readSmallUleb128();
            instanceFieldCount = reader.readSmallUleb128();
            directMethodCount = reader.readSmallUleb128();
            virtualMethodCount = reader.readSmallUleb128();
            staticFieldsOffset = reader.getOffset();
        }

    }


    @Override
    public String getType() {
        return dexFile.getType(dexFile.readSmallUint(classDefOffset + ClassDefItem.CLASS_OFFSET));
    }


    @Override
    public String getSuperclass() {
        return dexFile.getOptionalType(dexFile.readOptionalUint(classDefOffset + ClassDefItem.SUPERCLASS_OFFSET));
    }

    @Override
    public int getAccessFlags() {
        return dexFile.readSmallUint(classDefOffset + ClassDefItem.ACCESS_FLAGS_OFFSET);
    }


    @Override
    public String getSourceFile() {
        return dexFile.getOptionalString(dexFile.readOptionalUint(classDefOffset + ClassDefItem.SOURCE_FILE_OFFSET));
    }


    @Override
    public List<String> getInterfaces() {
        final int interfacesOffset = dexFile.readSmallUint(classDefOffset + ClassDefItem.INTERFACES_OFFSET);
        if (interfacesOffset > 0) {
            final int size = dexFile.readSmallUint(interfacesOffset);
            return new AbstractList<String>() {
                @Override

                public String get(int index) {
                    return dexFile.getType(dexFile.readUshort(interfacesOffset + 4 + (2*index)));
                }

                @Override public int size() { return size; }
            };
        }
        return ImmutableList.of();
    }


    @Override
    public Set<? extends DexBackedAnnotation> getAnnotations() {
        return getAnnotationsDirectory().getClassAnnotations();
    }


    @Override
    public Iterable<? extends DexBackedField> getStaticFields() {
        return getStaticFields(true);
    }


    public Iterable<? extends DexBackedField> getStaticFields(final boolean skipDuplicates) {
        if (staticFieldCount > 0) {
            DexReader reader = dexFile.readerAt(staticFieldsOffset);

            final AnnotationsDirectory annotationsDirectory = getAnnotationsDirectory();
            final int staticInitialValuesOffset =
                    dexFile.readSmallUint(classDefOffset + ClassDefItem.STATIC_VALUES_OFFSET);
            final int fieldsStartOffset = reader.getOffset();

            return new Iterable<DexBackedField>() {

                @Override
                public Iterator<DexBackedField> iterator() {
                    final AnnotationsDirectory.AnnotationIterator annotationIterator =
                            annotationsDirectory.getFieldAnnotationIterator();
                    final StaticInitialValueIterator staticInitialValueIterator =
                            StaticInitialValueIterator.newOrEmpty(dexFile, staticInitialValuesOffset);

                    return new VariableSizeLookaheadIterator<DexBackedField>(dexFile, fieldsStartOffset) {
                        private int count;
                        private FieldReference previousField;
                        private int previousIndex;


                        @Override
                        protected DexBackedField readNextItem(DexReader reader) {
                            while (true) {
                                if (++count > staticFieldCount) {
                                    instanceFieldsOffset = reader.getOffset();
                                    return endOfData();
                                }

                                DexBackedField item = new DexBackedField(reader, DexBackedClassDef.this,
                                        previousIndex, staticInitialValueIterator, annotationIterator);
                                FieldReference currentField = previousField;
                                FieldReference nextField = ImmutableFieldReference.of(item);

                                previousField = nextField;
                                previousIndex = item.fieldIndex;

                                if (skipDuplicates && currentField != null && currentField.equals(nextField)) {
                                    continue;
                                }

                                return item;
                            }
                        }
                    };
                }
            };
        } else {
            instanceFieldsOffset = staticFieldsOffset;
            return ImmutableSet.of();
        }
    }


    @Override
    public Iterable<? extends DexBackedField> getInstanceFields() {
        return getInstanceFields(true);
    }


    public Iterable<? extends DexBackedField> getInstanceFields(final boolean skipDuplicates) {
        if (instanceFieldCount > 0) {
            DexReader reader = dexFile.readerAt(getInstanceFieldsOffset());

            final AnnotationsDirectory annotationsDirectory = getAnnotationsDirectory();
            final int fieldsStartOffset = reader.getOffset();

            return new Iterable<DexBackedField>() {

                @Override
                public Iterator<DexBackedField> iterator() {
                    final AnnotationsDirectory.AnnotationIterator annotationIterator =
                            annotationsDirectory.getFieldAnnotationIterator();

                    return new VariableSizeLookaheadIterator<DexBackedField>(dexFile, fieldsStartOffset) {
                        private int count;
                        private FieldReference previousField;
                        private int previousIndex;


                        @Override
                        protected DexBackedField readNextItem(DexReader reader) {
                            while (true) {
                                if (++count > instanceFieldCount) {
                                    directMethodsOffset = reader.getOffset();
                                    return endOfData();
                                }

                                DexBackedField item = new DexBackedField(reader, DexBackedClassDef.this,
                                        previousIndex, annotationIterator);
                                FieldReference currentField = previousField;
                                FieldReference nextField = ImmutableFieldReference.of(item);

                                previousField = nextField;
                                previousIndex = item.fieldIndex;

                                if (skipDuplicates && currentField != null && currentField.equals(nextField)) {
                                    continue;
                                }

                                return item;
                            }
                        }
                    };
                }
            };
        } else {
            if (instanceFieldsOffset > 0) {
                directMethodsOffset = instanceFieldsOffset;
            }
            return ImmutableSet.of();
        }
    }


    @Override
    public Iterable<? extends DexBackedField> getFields() {
        return Iterables.concat(getStaticFields(), getInstanceFields());
    }


    @Override
    public Iterable<? extends DexBackedMethod> getDirectMethods() {
        return getDirectMethods(true);
    }


    public Iterable<? extends DexBackedMethod> getDirectMethods(final boolean skipDuplicates) {
        if (directMethodCount > 0) {
            DexReader reader = dexFile.readerAt(getDirectMethodsOffset());

            final AnnotationsDirectory annotationsDirectory = getAnnotationsDirectory();
            final int methodsStartOffset = reader.getOffset();

            return new Iterable<DexBackedMethod>() {

                @Override
                public Iterator<DexBackedMethod> iterator() {
                    final AnnotationsDirectory.AnnotationIterator methodAnnotationIterator =
                            annotationsDirectory.getMethodAnnotationIterator();
                    final AnnotationsDirectory.AnnotationIterator parameterAnnotationIterator =
                            annotationsDirectory.getParameterAnnotationIterator();

                    return new VariableSizeLookaheadIterator<DexBackedMethod>(dexFile, methodsStartOffset) {
                        private int count;
                        private MethodReference previousMethod;
                        private int previousIndex;


                        @Override
                        protected DexBackedMethod readNextItem(DexReader reader) {
                            while (true) {
                                if (++count > directMethodCount) {
                                    virtualMethodsOffset = reader.getOffset();
                                    return endOfData();
                                }

                                DexBackedMethod item = new DexBackedMethod(reader, DexBackedClassDef.this,
                                        previousIndex, methodAnnotationIterator, parameterAnnotationIterator);
                                MethodReference currentMethod = previousMethod;
                                MethodReference nextMethod = ImmutableMethodReference.of(item);

                                previousMethod = nextMethod;
                                previousIndex = item.methodIndex;

                                if (skipDuplicates && currentMethod != null && currentMethod.equals(nextMethod)) {
                                    continue;

                                }
                                return item;
                            }
                        }
                    };
                }
            };
        } else {
            if (directMethodsOffset > 0) {
                virtualMethodsOffset = directMethodsOffset;
            }
            return ImmutableSet.of();
        }
    }


    public Iterable<? extends DexBackedMethod> getVirtualMethods(final boolean skipDuplicates) {
        if (virtualMethodCount > 0) {
            DexReader reader = dexFile.readerAt(getVirtualMethodsOffset());

            final AnnotationsDirectory annotationsDirectory = getAnnotationsDirectory();
            final int methodsStartOffset = reader.getOffset();

            return new Iterable<DexBackedMethod>() {
                final AnnotationsDirectory.AnnotationIterator methodAnnotationIterator =
                        annotationsDirectory.getMethodAnnotationIterator();
                final AnnotationsDirectory.AnnotationIterator parameterAnnotationIterator =
                        annotationsDirectory.getParameterAnnotationIterator();


                @Override
                public Iterator<DexBackedMethod> iterator() {
                    return new VariableSizeLookaheadIterator<DexBackedMethod>(dexFile, methodsStartOffset) {
                        private int count;
                        private MethodReference previousMethod;
                        private int previousIndex;


                        @Override
                        protected DexBackedMethod readNextItem(DexReader reader) {
                            while (true) {
                                if (++count > virtualMethodCount) {
                                    return endOfData();
                                }

                                DexBackedMethod item = new DexBackedMethod(reader, DexBackedClassDef.this,
                                        previousIndex, methodAnnotationIterator, parameterAnnotationIterator);
                                MethodReference currentMethod = previousMethod;
                                MethodReference nextMethod = ImmutableMethodReference.of(item);

                                previousMethod = nextMethod;
                                previousIndex = item.methodIndex;

                                if (skipDuplicates && currentMethod != null && currentMethod.equals(nextMethod)) {
                                    continue;
                                }
                                return item;
                            }
                        }
                    };
                }
            };
        } else {
            return ImmutableSet.of();
        }
    }


    @Override
    public Iterable<? extends DexBackedMethod> getVirtualMethods() {
        return getVirtualMethods(true);
    }


    @Override
    public Iterable<? extends DexBackedMethod> getMethods() {
        return Iterables.concat(getDirectMethods(), getVirtualMethods());
    }

    private AnnotationsDirectory getAnnotationsDirectory() {
        if (annotationsDirectory == null) {
            int annotationsDirectoryOffset = dexFile.readSmallUint(classDefOffset + ClassDefItem.ANNOTATIONS_OFFSET);
            annotationsDirectory = AnnotationsDirectory.newOrEmpty(dexFile, annotationsDirectoryOffset);
        }
        return annotationsDirectory;
    }

    private int getInstanceFieldsOffset() {
        if (instanceFieldsOffset > 0) {
            return instanceFieldsOffset;
        }
        DexReader reader = new DexReader(dexFile, staticFieldsOffset);
        DexBackedField.skipFields(reader, staticFieldCount);
        instanceFieldsOffset = reader.getOffset();
        return instanceFieldsOffset;
    }

    private int getDirectMethodsOffset() {
        if (directMethodsOffset > 0) {
            return directMethodsOffset;
        }
        DexReader reader = dexFile.readerAt(getInstanceFieldsOffset());
        DexBackedField.skipFields(reader, instanceFieldCount);
        directMethodsOffset = reader.getOffset();
        return directMethodsOffset;
    }

    private int getVirtualMethodsOffset() {
        if (virtualMethodsOffset > 0) {
            return virtualMethodsOffset;
        }
        DexReader reader = dexFile.readerAt(getDirectMethodsOffset());
        DexBackedMethod.skipMethods(reader, directMethodCount);
        virtualMethodsOffset = reader.getOffset();
        return virtualMethodsOffset;
    }

    /**
     * Calculate and return the private size of a class definition.
     *
     * Calculated as: class_def_item size + type_id size + interfaces type_list +
     * annotations_directory_item overhead + class_data_item + static values overhead +
     * methods size + fields size
     *
     * @return size in bytes
     */
    public int getSize() {
        int size = 8 * 4; //class_def_item has 8 uint fields in dex files
        size += TypeIdItem.ITEM_SIZE; //type_ids size

        //add interface list size if any
        int interfacesLength = getInterfaces().size();
        if (interfacesLength > 0) {
            //add size of the type_list
            size += 4; //uint for size
            size += interfacesLength * 2; //ushort per type_item
        }

        //annotations directory size if it exists
        AnnotationsDirectory directory = getAnnotationsDirectory();
        if (!AnnotationsDirectory.EMPTY.equals(directory)) {
            size += 4 * 4; //4 uints in annotations_directory_item
            Set<? extends DexBackedAnnotation> classAnnotations = directory.getClassAnnotations();
            if (!classAnnotations.isEmpty()) {
                size += 4; //uint for size
                size += classAnnotations.size() * 4; //uint per annotation_off
                //TODO: should we add annotation_item size? what if it's shared?
            }
        }

        //static values and/or metadata
        int staticInitialValuesOffset =
                dexFile.readSmallUint(classDefOffset + ClassDefItem.STATIC_VALUES_OFFSET);
        if (staticInitialValuesOffset != 0) {
            DexReader reader = dexFile.readerAt(staticInitialValuesOffset);
            size += reader.peekSmallUleb128Size(); //encoded_array size field
        }

        //class_data_item
        int classDataOffset = dexFile.readSmallUint(classDefOffset + ClassDefItem.CLASS_DATA_OFFSET);
        if (classDataOffset > 0) {
            DexReader reader = dexFile.readerAt(classDataOffset);
            reader.readSmallUleb128(); //staticFieldCount
            reader.readSmallUleb128(); //instanceFieldCount
            reader.readSmallUleb128(); //directMethodCount
            reader.readSmallUleb128(); //virtualMethodCount
            size += reader.getOffset() - classDataOffset;
        }

        for (DexBackedField dexBackedField : getFields()) {
            size += dexBackedField.getSize();
        }

        for (DexBackedMethod dexBackedMethod : getMethods()) {
            size += dexBackedMethod.getSize();
        }
        return size;
    }
}
