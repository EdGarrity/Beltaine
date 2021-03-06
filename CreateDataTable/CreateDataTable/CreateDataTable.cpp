// CreateDataTable.cpp : This file contains the 'main' function. Program execution begins and ends there.
//

#include "Parameters.h"
#include "Domain/Table.h"

struct Parameters parameter;

int main(int argc, const char * argv[])
{
	domain::Table* pTable = nullptr;

	if (argc < 3)
	{
		std::cerr << "Usage: CreateDataTable filename from(YYYYMMDD) to(YYYYMMDD)" << std::endl;
		return 0;
	}

	parameter.archiveFilename = "C:\\Temp\\PushP\\push-3.1.0\\test - Search\\data.bin";
	//	parameter.sqlDateRangeFilter = "[Date] BETWEEN '1/3/2000  12:00:00 AM' AND '1/02/2010  12:00:00 AM'";
	parameter.sqlDateRangeFilter = "[Date] BETWEEN '2/4/1999  12:00:00 AM' AND '1/16/2015  12:00:00 AM'";

	std::string sFrom = argv[2];
	std::string sTo = argv[3];

	long from = std::stol(sFrom);
	long to = std::stol(sTo);

	//	domain::Table table;
	pTable = new domain::Table();

	pTable->ExportDataTable(argv[1], from, to);

	return 0;

}

// Run program: Ctrl + F5 or Debug > Start Without Debugging menu
// Debug program: F5 or Debug > Start Debugging menu

// Tips for Getting Started: 
//   1. Use the Solution Explorer window to add/manage files
//   2. Use the Team Explorer window to connect to source control
//   3. Use the Output window to see build output and other messages
//   4. Use the Error List window to view errors
//   5. Go to Project > Add New Item to create new code files, or Project > Add Existing Item to add existing code files to the project
//   6. In the future, to open this project again, go to File > Open > Project and select the .sln file
