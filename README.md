# Lucene Search Plugin

This plug-in allows you to search for console log and build display name etc.

Lucene is a powerful search library. Many popular search engines like Elastic Search and Solr are built upon it. Lucene Search Plugin uses Lucene library to index and search the console log content. It is embedded in the top search bar of Jenkins.

## Database Rebuild

If you have job data before the installation of Lucene Search, you need to rebuild the database manually. Note that any new builds after the installation of Lucene Search will be indexed automatically and the index of deleted builds and jobs will be deleted accordingly.

![image](https://user-images.githubusercontent.com/39845648/132785339-78b0b840-e99b-47ac-90fc-8528742d5c6d.png)


To index the existing data, you need to go to "Manage Jenkins -> Lucene Search Manager" and click rebuild. You can enter the jobs that you want to index. If nothing is entered, all jobs will be indexed by default. There are two modes of rebuild available. In "overwrite" mode, the indexer deletes old index of the job if there are any and then index the job. In "preserve" mode, the indexer searches for the build name. If the build is already indexed, it will skip to the next build. Otherwise, the build will be indexed.

The clean button will delete all your index. Please use it cautiously.

![image](https://user-images.githubusercontent.com/39845648/132785285-a2744f43-1a93-4c15-8687-e69fe674ee0e.png)


## Search Query

Lucene Search works in the top search bar of Jenkins. There are two kinds of search queries: single-job search and multi-job search. If you want to perform a search for a specific job, put the job name at the start of your query. If you enter only one word or the first word of your query is not recognized as a job name, the search will be conducted across different jobs.

There are five fields Lucene Search can search: console log, build display name, build parameter, project name, and build number. Here are the rules for the query:

1. The single-job search query is in the form “jobname queries”; jobname is the name of the job we want to search within; queries is a string of words we want to search for.
2. If the first word of the query is not recognized as an indexed job name, across-job search will be performed.
3. Boolean operators(AND is the default operator): 
   - AND
   - OR 
   - NOT
4. Wildcards:
   - "*" means zero or more characters
   - "?" means one character
4. You can limit the range of search by using keywords. The supported keywords are:
   - j : project name
   - d : build dispaly name
   - p : build parameter
   - n : build number 
   - c : build console log 

For example, if you want to search for builds in the job "test1", which have "1" in its display name and 
"bash" in its console log, the query should be "test1 d:1 c:bash". If you want to search for all builds whose display name starts with "linux" or ends with "linux", the query should be "linux* OR *linux".  

![image](https://user-images.githubusercontent.com/39845648/132785524-802adb56-c964-4aef-b215-59904668d3c7.png)

![image](https://user-images.githubusercontent.com/39845648/132785770-377100db-b6e9-433c-a161-2e344227e1a7.png)


## Search Result

The highlighted fragments will not show if your search range does not include the console content. 

The 'm' button will take you to the middle and 'b' to the bottom. 

## Customize the Plug-in

You can customize Lucene Search according to your needs. For example. if you want to add your own word delimiters, you can modify `CaseInsesitiveAnalyzer` class and add delimiters.

For more information on the query syntax, you can consult [Apache Lucene Query Parser Syntax](https://lucene.apache.org/core/2_9_4/queryparsersyntax.html).

## Version 

Please download release version 358.vf0bb3a3ef215 or other newer version. The old versions might not work properly. 
