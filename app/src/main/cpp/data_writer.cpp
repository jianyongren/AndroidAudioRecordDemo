#include "data_writer.h"

DataWriter::DataWriter(const char* filePath) : file_(fopen(filePath, "wb")) {}

DataWriter::~DataWriter() {
    if (file_) {
        fclose(file_);
    }
}

void DataWriter::write(const void* data, size_t size) {
    if (file_) {
        fwrite(data, 1, size, file_);
    }
} 